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

internal data class S3RemoteReconcileTuning(
    val pageSize: Int = S3EndpointProfile.GENERIC_S3.reconcilePageSize,
    val headLimit: Int = S3EndpointProfile.GENERIC_S3.reconcileHeadLimit,
    val headConcurrency: Int = S3EndpointProfile.GENERIC_S3.reconcileHeadConcurrency,
)

internal class S3RemoteShardPlanner {
    suspend fun plan(
        layout: com.lomo.data.sync.SyncDirectoryLayout,
        mode: S3LocalSyncMode,
        endpointProfile: S3EndpointProfile = S3EndpointProfile.GENERIC_S3,
        protocolState: S3SyncProtocolState,
        remoteIndexStore: S3RemoteIndexStore,
        shardStateStore: S3RemoteShardStateStore,
        now: Long = System.currentTimeMillis(),
    ): PlannedRemoteShardScan {
        val indexedRelativePaths =
            if (remoteIndexStore.remoteIndexEnabled) {
                remoteIndexStore
                    .readReconcileCandidates(S3_SHARD_PLANNING_SEED_LIMIT)
                    .map(S3RemoteIndexEntry::relativePath)
            } else {
                emptyList()
            }
        val baseScanPlan = buildRemoteScanPlan(layout, mode, indexedRelativePaths)
        val storedCursor = decodeRemoteScanCursor(protocolState.remoteScanCursor)
        val cursorShard = storedCursor?.let { cursor -> baseScanPlan.findByBucketId(cursor.bucketId) }
        val scanPlan =
            if (cursorShard != null || !shardStateStore.remoteShardStateEnabled) {
                baseScanPlan
            } else {
                val statesByBucketId =
                    shardStateStore
                        .readByBucketIds(baseScanPlan.map(S3RemoteScanShard::bucketId))
                        .associateBy(S3RemoteShardState::bucketId)
                prioritizeColdShards(
                    baseScanPlan = baseScanPlan,
                    statesByBucket = statesByBucketId,
                    endpointProfile = endpointProfile,
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
        statesByBucket: Map<String, S3RemoteShardState>,
        endpointProfile: S3EndpointProfile,
        now: Long,
    ): List<S3RemoteScanShard> {
        val originalOrder = baseScanPlan.mapIndexed { index, shard -> shard.bucketId to index }.toMap()
        return baseScanPlan.sortedWith(
            compareByDescending<S3RemoteScanShard> { shard ->
                shardRiskBand(statesByBucket[shard.bucketId], endpointProfile)
            }.thenByDescending { shard ->
                shardVerificationFailureRate(statesByBucket[shard.bucketId])
            }.thenByDescending { shard ->
                shardChangeRate(statesByBucket[shard.bucketId])
            }.thenBy { shard ->
                statesByBucket[shard.bucketId] != null
            }.thenByDescending { shard ->
                shardPriorityBand(statesByBucket[shard.bucketId], now)
            }.thenBy { shard ->
                statesByBucket[shard.bucketId]?.idleScanStreak ?: Int.MAX_VALUE
            }.thenBy { shard ->
                originalOrder.getValue(shard.bucketId)
            },
        )
    }

    private fun shardRiskBand(
        state: S3RemoteShardState?,
        endpointProfile: S3EndpointProfile,
    ): Int =
        when {
            state == null -> 0
            shardVerificationFailureRate(state) >= endpointProfile.verificationFailureThreshold -> 2
            shardChangeRate(state) >= endpointProfile.changePressureThreshold -> 1
            else -> 0
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
        state ?: return 0.0
        val denominator = state.lastObjectCount.takeIf { it > 0 } ?: return 0.0
        return state.lastChangeCount.toDouble() / denominator.toDouble()
    }

    private fun shardVerificationFailureRate(state: S3RemoteShardState?): Double {
        state ?: return 0.0
        val denominator = state.lastVerificationAttemptCount.takeIf { it > 0 } ?: return 0.0
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
        tuning: S3RemoteReconcileTuning = S3RemoteReconcileTuning(),
    ): ListedRemoteReconcilePage {
        val listPage =
            client.listPage(
                prefix = activeShard.remotePrefix(config, encodingSupport),
                continuationToken = continuationToken,
                maxKeys = tuning.pageSize,
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
        tuning: S3RemoteReconcileTuning = S3RemoteReconcileTuning(),
    ): HeadVerifiedRemoteReconcile {
        val hotCandidates =
            (activeShardCandidates + remoteIndexStore.readReconcileCandidates(tuning.headLimit))
                .distinctBy(S3RemoteIndexEntry::relativePath)
                .sortedWith(
                    compareByDescending<S3RemoteIndexEntry> { it.scanPriority }
                        .thenByDescending { it.lastVerifiedAt ?: Long.MIN_VALUE }
                        .thenBy(S3RemoteIndexEntry::relativePath),
                ).take(tuning.headLimit)
                .filterNot { entry ->
                    entry.relativePath in listedRemoteFiles
                }
        val remoteFiles = linkedMapOf<String, RemoteS3File>()
        val observedEntries = linkedMapOf<String, S3RemoteIndexEntry>()
        val missingRemotePaths = linkedSetOf<String>()
        val headResults =
            coroutineScope {
                val limiter = Semaphore(tuning.headConcurrency)
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

internal class S3RemoteReconcileTuner {
    fun tune(
        config: S3ResolvedConfig,
        protocolState: S3SyncProtocolState,
        activeShardState: S3RemoteShardState?,
    ): S3RemoteReconcileTuning {
        var pageSize = config.endpointProfile.reconcilePageSize
        var headLimit = config.endpointProfile.reconcileHeadLimit
        var headConcurrency = config.endpointProfile.reconcileHeadConcurrency
        if (protocolState.remoteScanCursor != null) {
            pageSize += config.endpointProfile.cursorPageBonus
        }
        activeShardState?.let { state ->
            if (
                state.changeRate() >= config.endpointProfile.changePressureThreshold &&
                state.lastDurationMs <= S3_TUNING_FAST_SCAN_MS
            ) {
                pageSize += S3_TUNING_HOT_SHARD_PAGE_BONUS
                headLimit += S3_TUNING_HOT_SHARD_HEAD_BONUS
                headConcurrency += S3_TUNING_HOT_SHARD_CONCURRENCY_BONUS
            }
            if (state.idleScanStreak >= S3_TUNING_IDLE_STREAK_THRESHOLD) {
                pageSize -= S3_TUNING_IDLE_SHARD_PAGE_PENALTY
            }
            if (state.verificationFailureRate() >= config.endpointProfile.verificationFailureThreshold) {
                headLimit = minOf(headLimit, S3_TUNING_FAILURE_HEAD_LIMIT_CAP)
                headConcurrency = minOf(headConcurrency, S3_TUNING_FAILURE_HEAD_CONCURRENCY_CAP)
            }
        }
        return S3RemoteReconcileTuning(
            pageSize = pageSize.coerceIn(S3_RECONCILE_PAGE_SIZE_MIN, S3_RECONCILE_PAGE_SIZE_MAX),
            headLimit = headLimit.coerceIn(S3_RECONCILE_HEAD_LIMIT_MIN, S3_RECONCILE_HEAD_LIMIT_MAX),
            headConcurrency =
                headConcurrency.coerceIn(
                    S3_RECONCILE_HEAD_CONCURRENCY_MIN,
                    S3_RECONCILE_HEAD_CONCURRENCY_MAX,
                ),
        )
    }
}

private fun S3RemoteShardState.changeRate(): Double =
    lastObjectCount
        .takeIf { it > 0 }
        ?.let { count -> lastChangeCount.toDouble() / count.toDouble() }
        ?: 0.0

private fun S3RemoteShardState.verificationFailureRate(): Double =
    lastVerificationAttemptCount
        .takeIf { it > 0 }
        ?.let { attempts -> lastVerificationFailureCount.toDouble() / attempts.toDouble() }
        ?: 0.0

private const val S3_RECONCILE_PAGE_SIZE_MIN = 128
private const val S3_RECONCILE_PAGE_SIZE_MAX = 512
private const val S3_RECONCILE_HEAD_LIMIT_MIN = 4
private const val S3_RECONCILE_HEAD_LIMIT_MAX = 24
private const val S3_RECONCILE_HEAD_CONCURRENCY_MIN = 1
private const val S3_RECONCILE_HEAD_CONCURRENCY_MAX = 6
private const val S3_REMOTE_SHARD_PRIORITY_WINDOW_MS = 15 * 60_000L
private const val S3_SHARD_PLANNING_SEED_LIMIT = 64
private const val S3_TUNING_FAST_SCAN_MS = 100L
private const val S3_TUNING_IDLE_STREAK_THRESHOLD = 3
private const val S3_TUNING_HOT_SHARD_PAGE_BONUS = 128
private const val S3_TUNING_HOT_SHARD_HEAD_BONUS = 8
private const val S3_TUNING_HOT_SHARD_CONCURRENCY_BONUS = 2
private const val S3_TUNING_IDLE_SHARD_PAGE_PENALTY = 64
private const val S3_TUNING_FAILURE_HEAD_LIMIT_CAP = 8
private const val S3_TUNING_FAILURE_HEAD_CONCURRENCY_CAP = 2
