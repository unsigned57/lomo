package com.lomo.data.repository

import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason

internal suspend fun refineTrackedMemoPlanWithContent(
    plan: S3SyncPlan,
    localFiles: Map<String, LocalS3File>,
    remoteFiles: Map<String, RemoteS3File>,
    metadataByPath: Map<String, S3SyncMetadataEntity>,
    client: com.lomo.data.s3.LomoS3Client,
    config: S3ResolvedConfig,
    encodingSupport: S3SyncEncodingSupport,
    fileBridgeScope: S3SyncFileBridgeScope,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    mode: S3LocalSyncMode,
): S3SyncPlan {
    val refinedActionsByPath = plan.actions.associateBy(S3SyncAction::path).toMutableMap()
    val localFingerprintCache = mutableMapOf<String, String?>()
    val remoteFingerprintCache = mutableMapOf<String, String?>()
    plan.actions
        .asSequence()
        .filter { action ->
            action.direction in TRACKED_MEMO_CONTENT_DIRECTIONS &&
                metadataByPath[action.path]?.localFingerprint != null &&
                action.path in localFiles &&
                action.path in remoteFiles &&
                isMemoPath(action.path, layout, mode)
        }.forEach { action ->
            when (
                resolveTrackedMemoActionWithContent(
                    path = action.path,
                    remote = requireNotNull(remoteFiles[action.path]),
                    baselineFingerprint = requireNotNull(metadataByPath[action.path]?.localFingerprint),
                    client = client,
                    config = config,
                    encodingSupport = encodingSupport,
                    fileBridgeScope = fileBridgeScope,
                    layout = layout,
                    localFingerprintCache = localFingerprintCache,
                    remoteFingerprintCache = remoteFingerprintCache,
                )
            ) {
                null -> Unit
                TrackedMemoResolution.EQUIVALENT -> refinedActionsByPath.remove(action.path)
                TrackedMemoResolution.LOCAL_NEWER ->
                    refinedActionsByPath[action.path] =
                        S3SyncAction(
                            path = action.path,
                            direction = S3SyncDirection.UPLOAD,
                            reason = S3SyncReason.LOCAL_NEWER,
                        )

                TrackedMemoResolution.REMOTE_NEWER ->
                    refinedActionsByPath[action.path] =
                        S3SyncAction(
                            path = action.path,
                            direction = S3SyncDirection.DOWNLOAD,
                            reason = S3SyncReason.REMOTE_NEWER,
                        )

                TrackedMemoResolution.CONFLICT ->
                    refinedActionsByPath[action.path] =
                        S3SyncAction(
                            path = action.path,
                            direction = S3SyncDirection.CONFLICT,
                            reason = S3SyncReason.CONFLICT,
                        )
            }
        }
    val refinedActions = refinedActionsByPath.values.sortedBy(S3SyncAction::path)
    return S3SyncPlan(
        actions = refinedActions,
        pendingChanges = refinedActions.count { action -> action.direction != S3SyncDirection.NONE },
    )
}

private suspend fun resolveTrackedMemoActionWithContent(
    path: String,
    remote: RemoteS3File,
    baselineFingerprint: String,
    client: com.lomo.data.s3.LomoS3Client,
    config: S3ResolvedConfig,
    encodingSupport: S3SyncEncodingSupport,
    fileBridgeScope: S3SyncFileBridgeScope,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    localFingerprintCache: MutableMap<String, String?>,
    remoteFingerprintCache: MutableMap<String, String?>,
): TrackedMemoResolution? {
    val localFingerprint =
        localFingerprintCache.getOrPut(path) {
            fileBridgeScope.readLocalBytes(path, layout)?.md5Hex()
        } ?: return null
    val remoteFingerprint =
        remoteFingerprintCache.getOrPut(path) {
            val fastFingerprint =
                remote.contentMd5 ?: resolveRemoteContentMd5(emptyMap(), remote.etag)
            if (fastFingerprint != null) {
                fastFingerprint
            } else if (remote.verified) {
                runNonFatalCatching {
                    val payload = client.getObject(remote.remotePath)
                    resolveRemoteContentMd5(payload.metadata, payload.eTag)
                        ?: encodingSupport.decodeContent(payload.bytes, config).md5Hex()
                }.getOrNull()
            } else {
                runNonFatalCatching {
                    client.getObjectMetadata(remote.remotePath)?.let { metadata ->
                        resolveRemoteContentMd5(metadata.metadata, metadata.eTag)
                    }
                }.getOrNull()
                    ?: runNonFatalCatching {
                        val payload = client.getObject(remote.remotePath)
                        resolveRemoteContentMd5(payload.metadata, payload.eTag)
                            ?: encodingSupport.decodeContent(payload.bytes, config).md5Hex()
                    }.getOrNull()
            }
        } ?: return null
    return when {
        localFingerprint == remoteFingerprint -> TrackedMemoResolution.EQUIVALENT
        localFingerprint == baselineFingerprint -> TrackedMemoResolution.REMOTE_NEWER
        remoteFingerprint == baselineFingerprint -> TrackedMemoResolution.LOCAL_NEWER
        else -> TrackedMemoResolution.CONFLICT
    }
}

private enum class TrackedMemoResolution {
    EQUIVALENT,
    LOCAL_NEWER,
    REMOTE_NEWER,
    CONFLICT,
}

private val TRACKED_MEMO_CONTENT_DIRECTIONS =
    setOf(
        S3SyncDirection.UPLOAD,
        S3SyncDirection.DOWNLOAD,
        S3SyncDirection.CONFLICT,
    )
