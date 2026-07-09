package com.lomo.data.repository

import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason

internal suspend fun refineTrackedMemoPlanWithContent(
    plan: S3SyncPlan,
    localFiles: Map<String, LocalS3File>,
    remoteFiles: Map<String, RemoteS3File>,
    metadataByPath: Map<String, S3SyncMetadataEntity>,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    localFingerprintSource: S3LocalFingerprintSource,
): S3SyncPlan {
    val refinedActionsByPath = plan.actions.associateBy(S3SyncAction::path).toMutableMap()
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
                    local = requireNotNull(localFiles[action.path]),
                    remote = requireNotNull(remoteFiles[action.path]),
                    metadata = requireNotNull(metadataByPath[action.path]),
                    localFingerprintSource = localFingerprintSource,
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
    local: LocalS3File,
    remote: RemoteS3File,
    metadata: S3SyncMetadataEntity,
    localFingerprintSource: S3LocalFingerprintSource,
): TrackedMemoResolution? {
    val baselineFingerprint = requireNotNull(metadata.localFingerprint)
    val remoteFingerprint = remote.memoContentFingerprint() ?: return null
    val localFingerprint =
        resolveLocalContentFingerprint(
            path = path,
            local = local,
            metadata = metadata,
            source = localFingerprintSource,
        ) ?: return null
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
