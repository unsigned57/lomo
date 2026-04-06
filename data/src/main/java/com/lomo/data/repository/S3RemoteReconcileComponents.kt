package com.lomo.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class PlannedRemoteShardScan(
    val scanPlan: List<S3RemoteScanShard>,
    val activeShard: S3RemoteScanShard?,
    val continuationToken: String?,
    val scanEpoch: Long,
)

internal data class ListedRemoteReconcilePage(
    val remoteFiles: Map<String, RemoteS3File>,
    val observedEntries: Map<String, S3RemoteIndexEntry>,
    val nextContinuationToken: String?,
)

internal data class HeadVerifiedRemoteReconcile(
    val remoteFiles: Map<String, RemoteS3File>,
    val observedEntries: Map<String, S3RemoteIndexEntry>,
    val missingRemotePaths: Set<String>,
)

internal class S3RemoteShardPlanner {
    suspend fun plan(
        layout: com.lomo.data.sync.SyncDirectoryLayout,
        mode: S3LocalSyncMode,
        protocolState: S3SyncProtocolState,
        remoteIndexStore: S3RemoteIndexStore,
        shardStateStore: S3RemoteShardStateStore,
        now: Long = System.currentTimeMillis(),
    ): PlannedRemoteShardScan {
        val indexedRelativePaths = remoteIndexStore.readAll().map(S3RemoteIndexEntry::relativePath)
        val baseScanPlan = buildRemoteScanPlan(layout, mode, indexedRelativePaths)
        val storedCursor = decodeRemoteScanCursor(protocolState.remoteScanCursor)
        val cursorShard = storedCursor?.let { cursor -> baseScanPlan.findByBucketId(cursor.bucketId) }
        val scanPlan =
            if (cursorShard != null || !shardStateStore.remoteShardStateEnabled) {
                baseScanPlan
            } else {
                prioritizeColdShards(
                    baseScanPlan = baseScanPlan,
                    shardStates = shardStateStore.readAll(),
                    now = now,
                )
            }
        return PlannedRemoteShardScan(
            scanPlan = scanPlan,
            activeShard = cursorShard ?: scanPlan.firstOrNull(),
            continuationToken = storedCursor?.takeIf { cursorShard != null }?.continuationToken,
            scanEpoch =
                if (cursorShard != null) {
                    protocolState.scanEpoch.takeIf { it > 0L } ?: nextScanEpoch(protocolState, now)
                } else {
                    nextScanEpoch(protocolState, now)
                },
        )
    }

    private fun prioritizeColdShards(
        baseScanPlan: List<S3RemoteScanShard>,
        shardStates: List<S3RemoteShardState>,
        now: Long,
    ): List<S3RemoteScanShard> {
        val originalOrder = baseScanPlan.mapIndexed { index, shard -> shard.bucketId to index }.toMap()
        val statesByBucket = shardStates.associateBy(S3RemoteShardState::bucketId)
        return baseScanPlan.sortedWith(
            compareBy<S3RemoteScanShard> { shard ->
                -shardPriorityBand(statesByBucket[shard.bucketId], now)
            }.thenByDescending { shard ->
                shardVerificationFailureRate(statesByBucket[shard.bucketId])
            }.thenByDescending { shard ->
                shardChangeRate(statesByBucket[shard.bucketId])
            }.thenBy { shard ->
                statesByBucket[shard.bucketId]?.idleScanStreak ?: Int.MAX_VALUE
            }.thenBy { shard ->
                statesByBucket[shard.bucketId]?.lastScannedAt ?: Long.MIN_VALUE
            }.thenBy { shard ->
                originalOrder.getValue(shard.bucketId)
            },
        )
    }

    private fun shardPriorityBand(
        state: S3RemoteShardState?,
        now: Long,
    ): Long {
        if (state == null) {
            return Long.MAX_VALUE
        }
        val ageMillis = (now - state.lastScannedAt).coerceAtLeast(0L)
        return ageMillis / S3_REMOTE_SHARD_PRIORITY_WINDOW_MS
    }

    private fun shardChangeRate(state: S3RemoteShardState?): Double {
        state ?: return -1.0
        val denominator = state.lastObjectCount.coerceAtLeast(1)
        return state.lastChangeCount.toDouble() / denominator.toDouble()
    }

    private fun shardVerificationFailureRate(state: S3RemoteShardState?): Double {
        state ?: return -1.0
        val denominator = state.lastVerificationAttemptCount.coerceAtLeast(1)
        return state.lastVerificationFailureCount.toDouble() / denominator.toDouble()
    }
}

internal class S3RemoteShardScanner {
    suspend fun listObservedRemoteEntries(
        client: com.lomo.data.s3.LomoS3Client,
        layout: com.lomo.data.sync.SyncDirectoryLayout,
        config: S3ResolvedConfig,
        mode: S3LocalSyncMode,
        encodingSupport: S3SyncEncodingSupport,
        activeShard: S3RemoteScanShard,
        continuationToken: String?,
        now: Long,
        scanEpoch: Long,
    ): ListedRemoteReconcilePage {
        val listPage =
            client.listPage(
                prefix = activeShard.remotePrefix(config, encodingSupport),
                continuationToken = continuationToken,
                maxKeys = S3_RECONCILE_PAGE_SIZE,
            )
        val remoteFiles = linkedMapOf<String, RemoteS3File>()
        val observedEntries = linkedMapOf<String, S3RemoteIndexEntry>()
        listPage.objects.forEach { remoteObject ->
            val decoded =
                runCatching {
                    encodingSupport.decodeRelativePath(remoteObject.key, config)
                }.getOrNull() ?: return@forEach
            val relativePath = normalizeRemoteRelativePath(decoded, layout, mode) ?: return@forEach
            remoteFiles[relativePath] = remoteObject.toVerifiedRemoteFile(relativePath, encodingSupport)
            observedEntries[relativePath] = remoteObject.toRemoteIndexEntry(relativePath, now, scanEpoch)
        }
        return ListedRemoteReconcilePage(
            remoteFiles = remoteFiles,
            observedEntries = observedEntries,
            nextContinuationToken = listPage.nextContinuationToken,
        )
    }
}

internal class S3RemoteVerificationGate {
    suspend fun verifyRemoteCandidates(
        client: com.lomo.data.s3.LomoS3Client,
        remoteIndexStore: S3RemoteIndexStore,
        activeShardCandidates: List<S3RemoteIndexEntry>,
        listedRemoteFiles: Map<String, RemoteS3File>,
        now: Long,
        scanEpoch: Long,
    ): HeadVerifiedRemoteReconcile {
        val hotCandidates =
            (activeShardCandidates + remoteIndexStore.readReconcileCandidates(S3_RECONCILE_HEAD_LIMIT))
                .distinctBy(S3RemoteIndexEntry::relativePath)
                .filterNot { entry ->
                    entry.relativePath in listedRemoteFiles
                }
        val remoteFiles = linkedMapOf<String, RemoteS3File>()
        val observedEntries = linkedMapOf<String, S3RemoteIndexEntry>()
        val missingRemotePaths = linkedSetOf<String>()
        val headResults =
            coroutineScope {
                val limiter = Semaphore(S3_RECONCILE_HEAD_CONCURRENCY)
                hotCandidates.map { entry ->
                    async {
                        limiter.withPermit {
                            entry.relativePath to
                                client.getObjectMetadata(entry.remotePath)?.toRemoteIndexEntry(
                                    relativePath = entry.relativePath,
                                    now = now,
                                    scanEpoch = scanEpoch,
                                    scanPriority = entry.scanPriority,
                                )
                        }
                    }
                }.awaitAll()
            }
        headResults.forEach { (relativePath, entry) ->
            if (entry == null) {
                missingRemotePaths += relativePath
            } else {
                observedEntries[relativePath] = entry
                remoteFiles[relativePath] = entry.toVerifiedRemoteFile()
            }
        }
        return HeadVerifiedRemoteReconcile(
            remoteFiles = remoteFiles,
            observedEntries = observedEntries,
            missingRemotePaths = missingRemotePaths,
        )
    }
}

private const val S3_RECONCILE_PAGE_SIZE = 256
private const val S3_RECONCILE_HEAD_LIMIT = 16
private const val S3_RECONCILE_HEAD_CONCURRENCY = 4
private const val S3_REMOTE_SHARD_PRIORITY_WINDOW_MS = 15 * 60_000L
