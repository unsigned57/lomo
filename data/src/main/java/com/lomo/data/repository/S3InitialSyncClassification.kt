package com.lomo.data.repository

import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.util.runNonFatalCatching
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
    client: com.lomo.data.s3.LomoS3Client,
    config: S3ResolvedConfig,
    encodingSupport: S3SyncEncodingSupport,
    fileBridgeScope: S3SyncFileBridgeScope,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    mode: S3LocalSyncMode,
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
            fileBridgeScope = fileBridgeScope,
            layout = layout,
            mode = mode,
            timestampToleranceMs = timestampToleranceMs,
        )

    val unresolvedCountBeforeFallback = accumulation.conflictCount + accumulation.memoFallbackPaths.size
    val shouldUseLightweightPreview =
        shouldUseLightweightInitialPreview(
            candidateCount = candidatePaths.size,
            unresolvedCount = unresolvedCountBeforeFallback,
        )

    if (!shouldUseLightweightPreview) {
        resolveMemoFallbackDecisions(
            accumulation = accumulation,
            localFiles = localFiles,
            remoteFiles = remoteFiles,
            client = client,
            config = config,
            encodingSupport = encodingSupport,
            fileBridgeScope = fileBridgeScope,
            layout = layout,
            timestampToleranceMs = timestampToleranceMs,
        )
    }

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
    val localBytesCache: MutableMap<String, ByteArray?>,
    val remoteBytesCache: MutableMap<String, ByteArray?>,
    val equivalentMetadataByPath: MutableMap<String, S3SyncMetadataEntity>,
    val resolvedActionsByPath: MutableMap<String, S3SyncAction>,
    val memoFallbackPaths: List<String>,
    val conflictCount: Int,
)

private suspend fun collectFastInitialDecisions(
    candidatePaths: List<String>,
    localFiles: Map<String, LocalS3File>,
    remoteFiles: Map<String, RemoteS3File>,
    fileBridgeScope: S3SyncFileBridgeScope,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    timestampToleranceMs: Long,
): InitialOverlapAccumulation {
    val localBytesCache = mutableMapOf<String, ByteArray?>()
    val remoteBytesCache = mutableMapOf<String, ByteArray?>()
    val equivalentMetadataByPath = linkedMapOf<String, S3SyncMetadataEntity>()
    val resolvedActionsByPath = linkedMapOf<String, S3SyncAction>()
    val memoFallbackPaths = mutableListOf<String>()
    var conflictCount = 0
    candidatePaths.forEach { path ->
        val local = requireNotNull(localFiles[path])
        val remote = requireNotNull(remoteFiles[path])
        when (
            resolveInitialOverlapFast(
                path = path,
                local = local,
                remote = remote,
                layout = layout,
                mode = mode,
                fileBridgeScope = fileBridgeScope,
                localBytesCache = localBytesCache,
                timestampToleranceMs = timestampToleranceMs,
            )
        ) {
            InitialOverlapDecision.EQUIVALENT ->
                equivalentMetadataByPath[path] =
                    initialEquivalentMetadata(path, local, remote, localBytesCache[path])

            InitialOverlapDecision.UPLOAD ->
                resolvedActionsByPath[path] = S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.LOCAL_ONLY)

            InitialOverlapDecision.DOWNLOAD ->
                resolvedActionsByPath[path] = S3SyncAction(path, S3SyncDirection.DOWNLOAD, S3SyncReason.REMOTE_ONLY)

            InitialOverlapDecision.NEEDS_MEMO_CONTENT_FALLBACK -> memoFallbackPaths += path
            InitialOverlapDecision.CONFLICT -> conflictCount += 1
        }
    }
    return InitialOverlapAccumulation(
        localBytesCache = localBytesCache,
        remoteBytesCache = remoteBytesCache,
        equivalentMetadataByPath = equivalentMetadataByPath,
        resolvedActionsByPath = resolvedActionsByPath,
        memoFallbackPaths = memoFallbackPaths,
        conflictCount = conflictCount,
    )
}

private suspend fun resolveMemoFallbackDecisions(
    accumulation: InitialOverlapAccumulation,
    localFiles: Map<String, LocalS3File>,
    remoteFiles: Map<String, RemoteS3File>,
    client: com.lomo.data.s3.LomoS3Client,
    config: S3ResolvedConfig,
    encodingSupport: S3SyncEncodingSupport,
    fileBridgeScope: S3SyncFileBridgeScope,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    timestampToleranceMs: Long,
) {
    accumulation.memoFallbackPaths.forEach { path ->
        val local = requireNotNull(localFiles[path])
        val remote = requireNotNull(remoteFiles[path])
        when (
            resolveMemoContentFallback(
                path = path,
                local = local,
                remote = remote,
                client = client,
                config = config,
                encodingSupport = encodingSupport,
                fileBridgeScope = fileBridgeScope,
                layout = layout,
                localBytesCache = accumulation.localBytesCache,
                remoteBytesCache = accumulation.remoteBytesCache,
                timestampToleranceMs = timestampToleranceMs,
            )
        ) {
            InitialOverlapDecision.EQUIVALENT ->
                accumulation.equivalentMetadataByPath[path] =
                    initialEquivalentMetadata(path, local, remote, accumulation.localBytesCache[path])

            InitialOverlapDecision.UPLOAD ->
                accumulation.resolvedActionsByPath[path] =
                    S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.LOCAL_ONLY)

            InitialOverlapDecision.DOWNLOAD ->
                accumulation.resolvedActionsByPath[path] =
                    S3SyncAction(path, S3SyncDirection.DOWNLOAD, S3SyncReason.REMOTE_ONLY)

            InitialOverlapDecision.NEEDS_MEMO_CONTENT_FALLBACK,
            InitialOverlapDecision.CONFLICT,
            -> Unit
        }
    }
}

private suspend fun resolveInitialOverlapFast(
    path: String,
    local: LocalS3File,
    remote: RemoteS3File,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    fileBridgeScope: S3SyncFileBridgeScope,
    localBytesCache: MutableMap<String, ByteArray?>,
    timestampToleranceMs: Long,
): InitialOverlapDecision {
    val remoteSize = remote.size
    val localSize = local.size
    if (remoteSize != null && localSize != null && remoteSize != localSize) {
        return newerSideDecision(local.lastModified, remote.lastModified, timestampToleranceMs)
    }
    normalizeSinglePartS3Md5(remote.etag)?.let { expectedMd5 ->
        val localMd5 =
            localBytesCache.getOrPut(path) {
                fileBridgeScope.readLocalBytes(path, layout)
            }?.md5Hex()
        if (localMd5 != null) {
            return if (localMd5 == expectedMd5) {
                InitialOverlapDecision.EQUIVALENT
            } else {
                newerSideDecision(local.lastModified, remote.lastModified, timestampToleranceMs)
            }
        }
    }
    if (isMemoPath(path, layout, mode)) {
        return InitialOverlapDecision.NEEDS_MEMO_CONTENT_FALLBACK
    }
    return InitialOverlapDecision.CONFLICT
}

private suspend fun resolveMemoContentFallback(
    path: String,
    local: LocalS3File,
    remote: RemoteS3File,
    client: com.lomo.data.s3.LomoS3Client,
    config: S3ResolvedConfig,
    encodingSupport: S3SyncEncodingSupport,
    fileBridgeScope: S3SyncFileBridgeScope,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    localBytesCache: MutableMap<String, ByteArray?>,
    remoteBytesCache: MutableMap<String, ByteArray?>,
    timestampToleranceMs: Long,
): InitialOverlapDecision {
    val localBytes =
        localBytesCache.getOrPut(path) {
            fileBridgeScope.readLocalBytes(path, layout)
        } ?: return InitialOverlapDecision.CONFLICT
    val remoteBytes =
        remoteBytesCache.getOrPut(path) {
            runNonFatalCatching {
                val payload = client.getObject(remote.remotePath)
                encodingSupport.decodeContent(payload.bytes, config)
            }.getOrNull()
        } ?: return InitialOverlapDecision.CONFLICT
    return if (localBytes.contentEquals(remoteBytes)) {
        InitialOverlapDecision.EQUIVALENT
    } else {
        newerSideDecision(local.lastModified, remote.lastModified, timestampToleranceMs)
    }
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
    remote: RemoteS3File,
    localBytes: ByteArray?,
): S3SyncMetadataEntity =
    S3SyncMetadataEntity(
        relativePath = path,
        remotePath = remote.remotePath,
        etag = remote.etag,
        remoteLastModified = remote.lastModified,
        localLastModified = local.lastModified,
        localSize = local.size,
        remoteSize = remote.size,
        localFingerprint = normalizeSinglePartS3Md5(remote.etag) ?: localBytes?.md5Hex(),
        lastSyncedAt = System.currentTimeMillis(),
        lastResolvedDirection = S3SyncMetadataEntity.NONE,
        lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
    )

private enum class InitialOverlapDecision {
    EQUIVALENT,
    UPLOAD,
    DOWNLOAD,
    NEEDS_MEMO_CONTENT_FALLBACK,
    CONFLICT,
}
private const val S3_INITIAL_HIGH_CONFLICT_MIN_CANDIDATES = 8
private const val S3_INITIAL_HIGH_CONFLICT_MIN_UNRESOLVED = 8
private const val S3_INITIAL_HIGH_CONFLICT_RATIO_PERCENT = 80
private const val PERCENT_SCALE = 100
