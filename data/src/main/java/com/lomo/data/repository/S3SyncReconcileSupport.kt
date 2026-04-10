package com.lomo.data.repository

import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncScanPolicy

internal suspend fun prepareRemoteReconcile(
    client: com.lomo.data.s3.LomoS3Client,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    config: S3ResolvedConfig,
    mode: S3LocalSyncMode,
    protocolState: S3SyncProtocolState,
    encodingSupport: S3SyncEncodingSupport,
    remoteIndexStore: S3RemoteIndexStore,
    shardStateStore: S3RemoteShardStateStore = DisabledS3RemoteShardStateStore,
    shardPlanner: S3RemoteShardPlanner = S3RemoteShardPlanner(),
    shardScanner: S3RemoteShardScanner = S3RemoteShardScanner(),
    verificationGate: S3RemoteVerificationGate = S3RemoteVerificationGate(),
    reconcileTuner: S3RemoteReconcileTuner = S3RemoteReconcileTuner(),
): PreparedRemoteReconcile {
    val now = System.currentTimeMillis()
    val plannedScan =
        shardPlanner.plan(
            layout = layout,
            mode = mode,
            endpointProfile = config.endpointProfile,
            protocolState = protocolState,
            remoteIndexStore = remoteIndexStore,
            shardStateStore = shardStateStore,
            now = now,
        )
    val activeShard = plannedScan.activeShard
    if (activeShard == null) {
        return PreparedRemoteReconcile(
            observedRemoteEntries = emptyMap(),
            missingRemotePaths = emptySet(),
            nextScanCursor = null,
            scanEpoch = plannedScan.scanEpoch,
            completedScanCycle = true,
        )
    }
    val activeShardState = resolveActiveShardState(shardStateStore, activeShard)
    val tuning =
        reconcileTuner.tune(
            config = config,
            protocolState = protocolState,
            activeShardState = activeShardState,
        )
    val pageStartedAt = System.currentTimeMillis()
    val listedPage =
        shardScanner.listObservedRemoteEntries(
            client = client,
            layout = layout,
            config = config,
            mode = mode,
            encodingSupport = encodingSupport,
            activeShard = activeShard,
            continuationToken = plannedScan.continuationToken,
            now = now,
            scanEpoch = plannedScan.scanEpoch,
            tuning = tuning,
        )
    val indexedEntriesForShard =
        if (listedPage.nextContinuationToken == null) {
            remoteIndexEntriesForShard(
                remoteIndexStore = remoteIndexStore,
                activeShard = activeShard,
            )
                .filterNot { entry -> entry.relativePath in listedPage.remoteFiles }
        } else {
            emptyList()
        }
    val headVerified =
        verificationGate.verifyRemoteCandidates(
            client = client,
            remoteIndexStore = remoteIndexStore,
            activeShardCandidates = indexedEntriesForShard,
            listedRemoteFiles = listedPage.remoteFiles,
            now = now,
            scanEpoch = plannedScan.scanEpoch,
            tuning = tuning,
        )
    recordShardScanState(
        shardStateStore = shardStateStore,
        activeShard = activeShard,
        listedPage = listedPage,
        headVerified = headVerified,
        startedAt = pageStartedAt,
        finishedAt = System.currentTimeMillis(),
    )
    val nextScanCursor =
        resolveNextScanCursor(
            scanPlan = plannedScan.scanPlan,
            activeShard = activeShard,
            nextContinuationToken = listedPage.nextContinuationToken,
        )
    return PreparedRemoteReconcile(
        candidatePaths = listedPage.remoteFiles.keys + headVerified.remoteFiles.keys + headVerified.missingRemotePaths,
        remoteFiles = listedPage.remoteFiles + headVerified.remoteFiles,
        observedRemoteEntries = listedPage.observedEntries + headVerified.observedEntries,
        missingRemotePaths = headVerified.missingRemotePaths,
        nextScanCursor = nextScanCursor,
        scanEpoch = plannedScan.scanEpoch,
        completedScanCycle = nextScanCursor == null,
    )
}

internal suspend fun applyRemoteIndexUpdates(
    remoteIndexStore: S3RemoteIndexStore,
    prepared: PreparedS3Sync,
    execution: S3ActionExecutionResult,
    now: Long,
) {
    if (!remoteIndexStore.remoteIndexEnabled) {
        return
    }
    val currentScanEpoch =
        prepared.remoteReconcileState?.scanEpoch
            ?: prepared.protocolState?.scanEpoch
            ?: 0L
    if (prepared.completeSnapshot) {
        replaceRemoteIndexSnapshot(
            remoteIndexStore = remoteIndexStore,
            snapshotEntries =
                execution.remoteFilesAfterSync.values.map { remoteFile ->
                    remoteFile.toRemoteIndexEntry(
                        now = now,
                        scanEpoch = prepared.remoteReconcileState?.scanEpoch ?: currentScanEpoch,
                    )
                },
        )
        return
    }
    val existingByPath =
        remoteIndexStore.readByRelativePaths(
            prepared.remoteFiles.keys +
                prepared.metadataByPath.keys +
                execution.remoteFilesAfterSync.keys +
                execution.unresolvedPaths +
                execution.failedPaths +
                prepared.observedMissingRemotePaths +
                prepared.remoteReconcileState?.missingRemotePaths.orEmpty(),
        ).associateBy(S3RemoteIndexEntry::relativePath)
    val observedEntries = linkedMapOf<String, S3RemoteIndexEntry>()
    prepared.remoteReconcileState?.observedRemoteEntries?.values?.forEach { entry ->
        observedEntries[entry.relativePath] = entry
    }
    execution.actionOutcomes.keys.forEach { path ->
        execution.remoteFilesAfterSync[path]?.let { remoteFile ->
            observedEntries[path] =
                remoteFile.toRemoteIndexEntry(
                    now = now,
                    scanEpoch = currentScanEpoch,
                    scanPriority = existingByPath[path]?.scanPriority ?: defaultScanPriority(path),
                ).promoteForRecentActivity(
                    now = now,
                    scanEpoch = currentScanEpoch,
                )
        }
    }
    val dirtyEntries =
        (execution.unresolvedPaths + execution.failedPaths).associateWith { path ->
            val existing =
                existingByPath[path]
                    ?: execution.remoteFilesAfterSync[path]?.toRemoteIndexEntry(
                        now = now,
                        scanEpoch = currentScanEpoch,
                    )
                    ?: prepared.remoteFiles[path]?.toRemoteIndexEntry(
                        now = now,
                        scanEpoch = currentScanEpoch,
                    )
            existing
                ?.promoteForDirtyFollowUp(now, currentScanEpoch)
                ?.promoteForRecentCandidate(now, currentScanEpoch)
        }.mapNotNull { (path, entry) -> entry?.let { path to it } }.toMap()
    val missingEntries =
        buildMap {
            prepared.remoteReconcileState?.missingRemotePaths.orEmpty().forEach { path ->
                if (path !in execution.remoteFilesAfterSync) {
                    put(path, buildMissingEntry(path, existingByPath[path], prepared, now, currentScanEpoch))
                }
            }
            prepared.observedMissingRemotePaths.forEach { path ->
                if (path !in execution.remoteFilesAfterSync) {
                    put(path, buildMissingEntry(path, existingByPath[path], prepared, now, currentScanEpoch))
                }
            }
            execution.actionOutcomes.forEach { (path, outcome) ->
                if (outcome.first == S3SyncDirection.DELETE_REMOTE) {
                    put(path, buildMissingEntry(path, existingByPath[path], prepared, now, currentScanEpoch))
                }
            }
        }
    remoteIndexStore.upsert((observedEntries + dirtyEntries + missingEntries).values)
    if (prepared.remoteReconcileState?.completedScanCycle == true) {
        remoteIndexStore.deleteOutsideScanEpoch(prepared.remoteReconcileState.scanEpoch)
    }
}

private suspend fun replaceRemoteIndexSnapshot(
    remoteIndexStore: S3RemoteIndexStore,
    snapshotEntries: Collection<S3RemoteIndexEntry>,
) {
    val snapshotEntriesByPath = snapshotEntries.associateBy(S3RemoteIndexEntry::relativePath)
    val existingEntriesByPath =
        remoteIndexStore
            .readByRelativePaths(snapshotEntriesByPath.keys)
            .associateBy(S3RemoteIndexEntry::relativePath)
    val changedEntries =
        snapshotEntriesByPath
            .mapNotNull { (path, entry) ->
                entry.takeIf { existingEntriesByPath[path] != entry }
            }
    val removedPaths = remoteIndexStore.readAllRelativePaths().toSet() - snapshotEntriesByPath.keys
    remoteIndexStore.upsert(changedEntries)
    remoteIndexStore.deleteByRelativePaths(removedPaths)
}

private fun buildMissingEntry(
    path: String,
    existing: S3RemoteIndexEntry?,
    prepared: PreparedS3Sync,
    now: Long,
    scanEpoch: Long,
): S3RemoteIndexEntry {
    val remotePath = existing?.remotePath ?: prepared.metadataByPath[path]?.remotePath ?: path
    return existing?.markMissingOnScan(now, scanEpoch)
        ?: missingRemoteIndexEntry(
            relativePath = path,
            remotePath = remotePath,
            now = now,
            scanEpoch = scanEpoch,
            previousPriority = prepared.remoteFiles[path]?.let { remote -> defaultScanPriority(remote.path) },
        )
}

internal fun shouldRunIncrementalReconcile(
    policy: S3SyncScanPolicy,
    config: S3ResolvedConfig,
    protocolState: S3SyncProtocolState,
    now: Long = System.currentTimeMillis(),
): Boolean =
    policy != S3SyncScanPolicy.FAST_ONLY &&
        (
            protocolState.remoteScanCursor != null ||
                protocolState.lastReconcileAt == null ||
                now - protocolState.lastReconcileAt >= config.endpointProfile.incrementalReconcileIntervalMs
        )

internal fun nextScanEpoch(
    protocolState: S3SyncProtocolState?,
    now: Long = System.currentTimeMillis(),
): Long = maxOf(now, (protocolState?.scanEpoch ?: 0L) + 1L)

internal suspend fun promoteDirtyRemoteCandidates(
    remoteIndexStore: S3RemoteIndexStore,
    relativePaths: Collection<String>,
    now: Long = System.currentTimeMillis(),
) {
    if (!remoteIndexStore.remoteIndexEnabled || relativePaths.isEmpty()) {
        return
    }
    val existingEntries = remoteIndexStore.readByRelativePaths(relativePaths)
    if (existingEntries.isEmpty()) {
        return
    }
    remoteIndexStore.upsert(existingEntries.map { entry -> entry.promoteForRecentCandidate(now) })
}

private suspend fun resolveActiveShardState(
    shardStateStore: S3RemoteShardStateStore,
    activeShard: S3RemoteScanShard,
): S3RemoteShardState? {
    if (!shardStateStore.remoteShardStateEnabled) {
        return null
    }
    shardStateStore.readByBucketId(activeShard.bucketId)?.let { return it }
    return shardStateStore.readMostSpecificAncestor(activeShard.relativePrefix)
}

private suspend fun remoteIndexEntriesForShard(
    remoteIndexStore: S3RemoteIndexStore,
    activeShard: S3RemoteScanShard,
): List<S3RemoteIndexEntry> =
    when {
        activeShard.relativePrefix != null ->
            remoteIndexStore.readByRelativePrefix(activeShard.relativePrefix)

        activeShard.bucketId == S3_SCAN_BUCKET_ROOT ->
            remoteIndexStore.readOutsideScanBuckets(
                excludedBuckets = listOf(S3_SCAN_BUCKET_MEMO, S3_SCAN_BUCKET_IMAGE, S3_SCAN_BUCKET_VOICE),
            )

        else -> remoteIndexStore.readAll()
    }

private fun resolveNextScanCursor(
    scanPlan: List<S3RemoteScanShard>,
    activeShard: S3RemoteScanShard,
    nextContinuationToken: String?,
): String? =
    when {
        nextContinuationToken != null ->
            encodeRemoteScanCursor(
                StoredS3RemoteScanCursor(
                    bucketId = activeShard.bucketId,
                    continuationToken = nextContinuationToken,
                ),
            )

        else ->
            scanPlan.nextAfter(activeShard)?.let { nextShard ->
                encodeRemoteScanCursor(StoredS3RemoteScanCursor(bucketId = nextShard.bucketId))
            }
    }

private suspend fun recordShardScanState(
    shardStateStore: S3RemoteShardStateStore,
    activeShard: S3RemoteScanShard,
    listedPage: ListedRemoteReconcilePage,
    headVerified: HeadVerifiedRemoteReconcile,
    startedAt: Long,
    finishedAt: Long,
) {
    if (!shardStateStore.remoteShardStateEnabled) {
        return
    }
    val previousState = shardStateStore.readByBucketId(activeShard.bucketId)
    val changeCount =
        listedPage.observedEntries.size +
            headVerified.observedEntries.size +
            headVerified.missingRemotePaths.size
    val verificationAttemptCount =
        headVerified.observedEntries.size +
            headVerified.missingRemotePaths.size
    shardStateStore.upsert(
        listOf(
            S3RemoteShardState(
                bucketId = activeShard.bucketId,
                relativePrefix = activeShard.relativePrefix,
                lastScannedAt = finishedAt,
                lastObjectCount = listedPage.remoteFiles.size,
                lastDurationMs = (finishedAt - startedAt).coerceAtLeast(0L),
                lastChangeCount = changeCount,
                idleScanStreak =
                    if (changeCount == 0) {
                        (previousState?.idleScanStreak ?: 0) + 1
                    } else {
                        0
                    },
                lastVerificationAttemptCount = verificationAttemptCount,
                lastVerificationFailureCount = headVerified.missingRemotePaths.size,
            ),
        ),
    )
}
