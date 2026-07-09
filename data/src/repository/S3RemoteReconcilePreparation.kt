package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao

internal suspend fun prepareRemoteReconcile(
    client: com.lomo.data.s3.LomoS3Client,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    config: S3ResolvedConfig,
    mode: S3LocalSyncMode,
    protocolState: S3SyncProtocolState,
    encodingSupport: S3SyncEncodingSupport,
    objectKeyPolicy: S3RemoteObjectKeyPolicy,
    metadataDao: S3SyncMetadataDao,
    remoteIndexStore: S3RemoteIndexStore,
    shardStateStore: S3RemoteShardStateStore,
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
    val tuning =
        reconcileTuner.tune(
            config = config,
            protocolState = protocolState,
            activeShardState = resolveActiveShardState(shardStateStore, activeShard),
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
    val headVerified =
        verifyUnlistedShardEntries(
            client = client,
            config = config,
            objectKeyPolicy = objectKeyPolicy,
            remoteIndexStore = remoteIndexStore,
            activeShard = activeShard,
            listedPage = listedPage,
            now = now,
            scanEpoch = plannedScan.scanEpoch,
            verificationGate = verificationGate,
            tuning = tuning,
        )
    val replanCandidateListedPaths =
        resolveReplanCandidateListedPaths(
            listedRemoteFiles = listedPage.remoteFiles,
            metadataDao = metadataDao,
            remoteIndexStore = remoteIndexStore,
        )
    recordShardScanState(
        shardStateStore = shardStateStore,
        activeShard = activeShard,
        listedPage = listedPage,
        replanCandidateListedCount = replanCandidateListedPaths.size,
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
        candidatePaths = replanCandidateListedPaths + headVerified.remoteFiles.keys + headVerified.missingRemotePaths,
        remoteFiles = listedPage.remoteFiles + headVerified.remoteFiles,
        observedRemoteEntries = listedPage.observedEntries + headVerified.observedEntries,
        missingRemotePaths = headVerified.missingRemotePaths,
        nextScanCursor = nextScanCursor,
        scanEpoch = plannedScan.scanEpoch,
        completedScanCycle = nextScanCursor == null,
    )
}

private suspend fun verifyUnlistedShardEntries(
    client: com.lomo.data.s3.LomoS3Client,
    config: S3ResolvedConfig,
    objectKeyPolicy: S3RemoteObjectKeyPolicy,
    remoteIndexStore: S3RemoteIndexStore,
    activeShard: S3RemoteScanShard,
    listedPage: ListedRemoteReconcilePage,
    now: Long,
    scanEpoch: Long,
    verificationGate: S3RemoteVerificationGate,
    tuning: S3RemoteReconcileTuning,
): HeadVerifiedRemoteReconcile {
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
    return verificationGate.verifyRemoteCandidates(
        client = client,
        remoteIndexStore = remoteIndexStore,
        activeShardCandidates = indexedEntriesForShard,
        listedRemoteFiles = listedPage.remoteFiles,
        config = config,
        objectKeyPolicy = objectKeyPolicy,
        now = now,
        scanEpoch = scanEpoch,
        tuning = tuning,
    )
}

/**
 * Behavior Contract: a listed remote object re-enters planning only when it diverges from the
 * persisted sync baseline. Etag equality with the baseline means the remote content is the exact
 * object recorded at the last sync, so listing-time server timestamps (which always drift from the
 * rclone-style mtime persisted at upload) must not replan, re-stat, or re-download it. Local edits
 * on such paths remain the responsibility of the change journal and the bounded local audit, and
 * dirty or missing remote-index entries always replan regardless of etag equality.
 */
private suspend fun resolveReplanCandidateListedPaths(
    listedRemoteFiles: Map<String, RemoteS3File>,
    metadataDao: S3SyncMetadataDao,
    remoteIndexStore: S3RemoteIndexStore,
): Set<String> {
    if (listedRemoteFiles.isEmpty()) {
        return emptySet()
    }
    val baselineByPath =
        metadataDao
            .getByRelativePaths(listedRemoteFiles.keys.toList())
            .associateBy { entity -> entity.relativePath }
    if (baselineByPath.isEmpty()) {
        return listedRemoteFiles.keys
    }
    val flaggedPaths =
        if (remoteIndexStore.remoteIndexEnabled) {
            remoteIndexStore
                .readByRelativePaths(baselineByPath.keys)
                .filter { entry -> entry.dirtySuspect || entry.missingOnLastScan }
                .map(S3RemoteIndexEntry::relativePath)
                .toSet()
        } else {
            emptySet()
        }
    return listedRemoteFiles
        .filterTo(linkedMapOf()) { (path, remote) ->
            val baselineEtag = baselineByPath[path]?.etag
            baselineEtag == null ||
                remote.etag == null ||
                remote.etag != baselineEtag ||
                path in flaggedPaths
        }.keys
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
    replanCandidateListedCount: Int,
    headVerified: HeadVerifiedRemoteReconcile,
    startedAt: Long,
    finishedAt: Long,
) {
    if (!shardStateStore.remoteShardStateEnabled) {
        return
    }
    val previousState = shardStateStore.readByBucketId(activeShard.bucketId)
    val changeCount =
        replanCandidateListedCount +
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
