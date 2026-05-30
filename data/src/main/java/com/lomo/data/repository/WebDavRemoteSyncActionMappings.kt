package com.lomo.data.repository

import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncReason

internal fun WebDavSyncAction.toRemoteSyncAction(): RemoteSyncAction =
    RemoteSyncAction(
        path = path,
        direction = direction.toRemoteSyncDirection(),
        reason = reason.toRemoteSyncReason(),
    )

internal fun Map<String, WebDavSyncAction>.toWebDavRemoteSyncActions():
    Map<String, RemoteSyncAction> =
    mapValues { (_, action) -> action.toRemoteSyncAction() }

internal fun RemoteSyncPlan.toWebDavPlan(): WebDavSyncPlan =
    WebDavSyncPlan(
        actions = actions.map(RemoteSyncAction::toWebDavAction),
        pendingChanges = pendingChanges,
    )

internal fun RemoteSyncAction.toWebDavAction(): WebDavSyncAction =
    WebDavSyncAction(
        path = path,
        direction = direction.toWebDavDirection(),
        reason = reason.toWebDavReason(),
    )

private fun WebDavSyncDirection.toRemoteSyncDirection(): RemoteSyncDirection =
    when (this) {
        WebDavSyncDirection.NONE -> RemoteSyncDirection.NONE
        WebDavSyncDirection.UPLOAD -> RemoteSyncDirection.UPLOAD
        WebDavSyncDirection.DOWNLOAD -> RemoteSyncDirection.DOWNLOAD
        WebDavSyncDirection.DELETE_LOCAL -> RemoteSyncDirection.DELETE_LOCAL
        WebDavSyncDirection.DELETE_REMOTE -> RemoteSyncDirection.DELETE_REMOTE
        WebDavSyncDirection.CONFLICT -> RemoteSyncDirection.CONFLICT
    }

private fun RemoteSyncDirection.toWebDavDirection(): WebDavSyncDirection = enumValueOf(name)

private fun WebDavSyncReason.toRemoteSyncReason(): RemoteSyncReason =
    when (this) {
        WebDavSyncReason.UNCHANGED -> RemoteSyncReason.UNCHANGED
        WebDavSyncReason.LOCAL_ONLY -> RemoteSyncReason.LOCAL_ONLY
        WebDavSyncReason.REMOTE_ONLY -> RemoteSyncReason.REMOTE_ONLY
        WebDavSyncReason.LOCAL_NEWER -> RemoteSyncReason.LOCAL_NEWER
        WebDavSyncReason.REMOTE_NEWER -> RemoteSyncReason.REMOTE_NEWER
        WebDavSyncReason.LOCAL_DELETED -> RemoteSyncReason.LOCAL_DELETED
        WebDavSyncReason.REMOTE_DELETED -> RemoteSyncReason.REMOTE_DELETED
        WebDavSyncReason.SAME_TIMESTAMP -> RemoteSyncReason.SAME_TIMESTAMP
        WebDavSyncReason.CONFLICT -> RemoteSyncReason.CONFLICT
    }

private fun RemoteSyncReason.toWebDavReason(): WebDavSyncReason = enumValueOf(name)
