package com.lomo.data.repository

import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason

internal data class S3InitialSyncClassification(
    val equivalentMetadataByPath: Map<String, S3SyncMetadataEntity> = emptyMap(),
    val resolvedActionsByPath: Map<String, S3SyncAction> = emptyMap(),
    val lightweightConflictPreview: Boolean = false,
)

internal suspend fun classifyInitialOverlaps(
    localFiles: Map<String, LocalS3File>,
    remoteFiles: Map<String, RemoteS3File>,
    metadataByPath: Map<String, S3SyncMetadataEntity>,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    localFingerprintSource: S3LocalFingerprintSource,
    timestampToleranceMs: Long = 1000L,
): S3InitialSyncClassification {
    val candidatePaths =
        localFiles.keys
            .intersect(remoteFiles.keys)
            .asSequence()
            .filterNot(metadataByPath::containsKey)
            .sorted()
            .toList()
    if (candidatePaths.isEmpty()) {
        return S3InitialSyncClassification()
    }
    val accumulation =
        collectFastInitialDecisions(
            candidatePaths = candidatePaths,
            localFiles = localFiles,
            remoteFiles = remoteFiles,
            layout = layout,
            mode = mode,
            localFingerprintSource = localFingerprintSource,
            timestampToleranceMs = timestampToleranceMs,
        )

    val unresolvedCountBeforeFallback = accumulation.conflictCount
    val shouldUseLightweightPreview =
        shouldUseLightweightInitialPreview(
            candidateCount = candidatePaths.size,
            unresolvedCount = unresolvedCountBeforeFallback,
        )

    val finalConflictCount =
        candidatePaths.size -
            accumulation.equivalentMetadataByPath.size -
            accumulation.resolvedActionsByPath.size
    return S3InitialSyncClassification(
        equivalentMetadataByPath = accumulation.equivalentMetadataByPath,
        resolvedActionsByPath = accumulation.resolvedActionsByPath,
        lightweightConflictPreview =
            shouldUseLightweightPreview ||
                shouldUseLightweightInitialPreview(
                    candidateCount = candidatePaths.size,
                    unresolvedCount = finalConflictCount,
                ),
    )
}

private data class InitialOverlapAccumulation(
    val equivalentMetadataByPath: MutableMap<String, S3SyncMetadataEntity>,
    val resolvedActionsByPath: MutableMap<String, S3SyncAction>,
    val conflictCount: Int,
)

private suspend fun collectFastInitialDecisions(
    candidatePaths: List<String>,
    localFiles: Map<String, LocalS3File>,
    remoteFiles: Map<String, RemoteS3File>,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    localFingerprintSource: S3LocalFingerprintSource,
    timestampToleranceMs: Long,
): InitialOverlapAccumulation {
    val equivalentMetadataByPath = linkedMapOf<String, S3SyncMetadataEntity>()
    val resolvedActionsByPath = linkedMapOf<String, S3SyncAction>()
    var conflictCount = 0
    candidatePaths.forEach { path ->
        val local = requireNotNull(localFiles[path])
        val remote = requireNotNull(remoteFiles[path])
        val localFingerprint =
            resolveInitialOverlapLocalFingerprint(
                path = path,
                local = local,
                remote = remote,
                layout = layout,
                mode = mode,
                localFingerprintSource = localFingerprintSource,
            )
        when (
            resolveInitialOverlapFast(
                path = path,
                local = local,
                localFingerprint = localFingerprint,
                remote = remote,
                layout = layout,
                mode = mode,
                timestampToleranceMs = timestampToleranceMs,
            )
        ) {
            InitialOverlapDecision.EQUIVALENT ->
                equivalentMetadataByPath[path] =
                    initialEquivalentMetadata(path, local, localFingerprint, remote)

            InitialOverlapDecision.UPLOAD ->
                resolvedActionsByPath[path] = S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.LOCAL_ONLY)

            InitialOverlapDecision.DOWNLOAD ->
                resolvedActionsByPath[path] = S3SyncAction(path, S3SyncDirection.DOWNLOAD, S3SyncReason.REMOTE_ONLY)

            InitialOverlapDecision.CONFLICT -> conflictCount += 1
        }
    }
    return InitialOverlapAccumulation(
        equivalentMetadataByPath = equivalentMetadataByPath,
        resolvedActionsByPath = resolvedActionsByPath,
        conflictCount = conflictCount,
    )
}

private suspend fun resolveInitialOverlapLocalFingerprint(
    path: String,
    local: LocalS3File,
    remote: RemoteS3File,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    localFingerprintSource: S3LocalFingerprintSource,
): String? {
    local.localFingerprint?.let { return it }
    val sizesDiffer = remote.size != null && local.size != null && remote.size != local.size
    if (sizesDiffer || !isMemoPath(path, layout, mode) || remote.memoContentFingerprint() == null) {
        return null
    }
    return localFingerprintSource.fingerprint(path, local)
}

private fun resolveInitialOverlapFast(
    path: String,
    local: LocalS3File,
    localFingerprint: String?,
    remote: RemoteS3File,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    timestampToleranceMs: Long,
): InitialOverlapDecision {
    val remoteSize = remote.size
    val localSize = local.size
    if (remoteSize != null && localSize != null && remoteSize != localSize) {
        return newerSideDecision(local.lastModified, remote.lastModified, timestampToleranceMs)
    }
    if (isMemoPath(path, layout, mode)) {
        val remoteFingerprint = remote.memoContentFingerprint()
        if (localFingerprint != null && remoteFingerprint != null) {
            return if (localFingerprint == remoteFingerprint) {
                InitialOverlapDecision.EQUIVALENT
            } else {
                newerSideDecision(local.lastModified, remote.lastModified, timestampToleranceMs)
            }
        }
    }
    return InitialOverlapDecision.CONFLICT
}

private fun shouldUseLightweightInitialPreview(
    candidateCount: Int,
    unresolvedCount: Int,
): Boolean =
    candidateCount >= S3_INITIAL_HIGH_CONFLICT_MIN_CANDIDATES &&
        unresolvedCount >= S3_INITIAL_HIGH_CONFLICT_MIN_UNRESOLVED &&
        unresolvedCount * PERCENT_SCALE >= candidateCount * S3_INITIAL_HIGH_CONFLICT_RATIO_PERCENT

private fun newerSideDecision(
    localLastModified: Long,
    remoteLastModified: Long?,
    timestampToleranceMs: Long,
): InitialOverlapDecision {
    val remoteTimestamp = remoteLastModified ?: return InitialOverlapDecision.UPLOAD
    return when {
        localLastModified - remoteTimestamp > timestampToleranceMs -> InitialOverlapDecision.UPLOAD
        remoteTimestamp - localLastModified > timestampToleranceMs -> InitialOverlapDecision.DOWNLOAD
        else -> InitialOverlapDecision.CONFLICT
    }
}

private fun initialEquivalentMetadata(
    path: String,
    local: LocalS3File,
    localFingerprint: String?,
    remote: RemoteS3File,
): S3SyncMetadataEntity =
    S3SyncMetadataEntity(
        relativePath = path,
        remotePath = remote.remotePath,
        etag = remote.etag,
        remoteLastModified = remote.lastModified,
        localLastModified = local.lastModified,
        localSize = local.size,
        remoteSize = remote.size,
        localFingerprint = localFingerprint ?: remote.memoContentFingerprint(),
        lastSyncedAt = System.currentTimeMillis(),
        lastResolvedDirection = S3SyncMetadataEntity.NONE,
        lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
    )

private enum class InitialOverlapDecision {
    EQUIVALENT,
    UPLOAD,
    DOWNLOAD,
    CONFLICT,
}
private const val S3_INITIAL_HIGH_CONFLICT_MIN_CANDIDATES = 8
private const val S3_INITIAL_HIGH_CONFLICT_MIN_UNRESOLVED = 8
private const val S3_INITIAL_HIGH_CONFLICT_RATIO_PERCENT = 80
private const val PERCENT_SCALE = 100
