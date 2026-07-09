package com.lomo.data.repository

import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason

internal fun S3SyncAction.toRemoteSyncAction(): RemoteSyncAction =
    RemoteSyncAction(
        path = path,
        direction = direction.toRemoteSyncDirection(),
        reason = reason.toRemoteSyncReason(),
    )

internal fun Map<String, S3SyncAction>.toS3RemoteSyncActions(): Map<String, RemoteSyncAction> =
    mapValues { (_, action) -> action.toRemoteSyncAction() }

internal fun RemoteSyncPlan.toS3Plan(): S3SyncPlan =
    S3SyncPlan(
        actions = actions.map { action -> action.toS3Action() },
        pendingChanges = pendingChanges,
    )

internal fun RemoteSyncAction.toS3Action(): S3SyncAction =
    S3SyncAction(
        path = path,
        direction = direction.toS3Direction(),
        reason = reason.toS3Reason(),
    )

private fun S3SyncDirection.toRemoteSyncDirection(): RemoteSyncDirection =
    when (this) {
        S3SyncDirection.NONE -> RemoteSyncDirection.NONE
        S3SyncDirection.UPLOAD -> RemoteSyncDirection.UPLOAD
        S3SyncDirection.DOWNLOAD -> RemoteSyncDirection.DOWNLOAD
        S3SyncDirection.DELETE_LOCAL -> RemoteSyncDirection.DELETE_LOCAL
        S3SyncDirection.DELETE_REMOTE -> RemoteSyncDirection.DELETE_REMOTE
        S3SyncDirection.CONFLICT -> RemoteSyncDirection.CONFLICT
    }

private fun RemoteSyncDirection.toS3Direction(): S3SyncDirection = enumValueOf(name)

private fun S3SyncReason.toRemoteSyncReason(): RemoteSyncReason =
    when (this) {
        S3SyncReason.UNCHANGED -> RemoteSyncReason.UNCHANGED
        S3SyncReason.LOCAL_ONLY -> RemoteSyncReason.LOCAL_ONLY
        S3SyncReason.REMOTE_ONLY -> RemoteSyncReason.REMOTE_ONLY
        S3SyncReason.LOCAL_NEWER -> RemoteSyncReason.LOCAL_NEWER
        S3SyncReason.REMOTE_NEWER -> RemoteSyncReason.REMOTE_NEWER
        S3SyncReason.LOCAL_DELETED -> RemoteSyncReason.LOCAL_DELETED
        S3SyncReason.REMOTE_DELETED -> RemoteSyncReason.REMOTE_DELETED
        S3SyncReason.SAME_TIMESTAMP -> RemoteSyncReason.SAME_TIMESTAMP
        S3SyncReason.CONFLICT -> RemoteSyncReason.CONFLICT
    }

private fun RemoteSyncReason.toS3Reason(): S3SyncReason = enumValueOf(name)
