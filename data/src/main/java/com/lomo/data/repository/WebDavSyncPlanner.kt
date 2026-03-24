package com.lomo.data.repository

import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncOutcome
import com.lomo.domain.model.WebDavSyncReason
import kotlin.math.abs

data class LocalWebDavFile(
    val path: String,
    val lastModified: Long,
)

data class RemoteWebDavFile(
    val path: String,
    val etag: String?,
    val lastModified: Long?,
)

data class WebDavSyncAction(
    val path: String,
    val direction: WebDavSyncDirection,
    val reason: WebDavSyncReason,
)

data class WebDavSyncPlan(
    val actions: List<WebDavSyncAction>,
    val pendingChanges: Int,
)

class WebDavSyncPlanner(
    private val timestampToleranceMs: Long = 1000L,
) {
    fun plan(
        localFiles: Map<String, LocalWebDavFile>,
        remoteFiles: Map<String, RemoteWebDavFile>,
        metadata: Map<String, WebDavSyncMetadataEntity>,
    ): WebDavSyncPlan {
        val actions =
            buildList {
                (localFiles.keys + remoteFiles.keys + metadata.keys)
                    .sorted()
                    .forEach { path ->
                        createAction(
                            path = path,
                            local = localFiles[path],
                            remote = remoteFiles[path],
                            metadata = metadata[path],
                        )?.let(::add)
                    }
            }

        return WebDavSyncPlan(
            actions = actions,
            pendingChanges = actions.count { it.direction != WebDavSyncDirection.NONE },
        )
    }

    private fun createAction(
        path: String,
        local: LocalWebDavFile?,
        remote: RemoteWebDavFile?,
        metadata: WebDavSyncMetadataEntity?,
    ): WebDavSyncAction? =
        when {
            local != null && remote != null -> handleBothPresent(path, local, remote, metadata)
            local != null -> handleLocalOnly(path, local, metadata)
            remote != null -> handleRemoteOnly(path, remote, metadata)
            else -> null
        }

    private fun handleBothPresent(
        path: String,
        local: LocalWebDavFile,
        remote: RemoteWebDavFile,
        metadata: WebDavSyncMetadataEntity?,
    ): WebDavSyncAction? {
        if (metadata == null) {
            return newerWins(path, local.lastModified, remote.lastModified ?: 0L)
        }

        val localChanged = changed(local.lastModified, metadata.localLastModified)
        val remoteChanged = changed(remote.lastModified, metadata.remoteLastModified) || remote.etag != metadata.etag

        return when {
            !localChanged && !remoteChanged -> null
            localChanged && !remoteChanged ->
                WebDavSyncAction(path, WebDavSyncDirection.UPLOAD, WebDavSyncReason.LOCAL_ONLY)

            !localChanged && remoteChanged ->
                WebDavSyncAction(path, WebDavSyncDirection.DOWNLOAD, WebDavSyncReason.REMOTE_ONLY)

            else -> WebDavSyncAction(path, WebDavSyncDirection.CONFLICT, WebDavSyncReason.CONFLICT)
        }
    }

    private fun handleLocalOnly(
        path: String,
        local: LocalWebDavFile,
        metadata: WebDavSyncMetadataEntity?,
    ): WebDavSyncAction =
        when {
            metadata == null -> {
                WebDavSyncAction(path, WebDavSyncDirection.UPLOAD, WebDavSyncReason.LOCAL_ONLY)
            }

            !changed(local.lastModified, metadata.localLastModified) -> {
                WebDavSyncAction(path, WebDavSyncDirection.DELETE_LOCAL, WebDavSyncReason.REMOTE_DELETED)
            }

            else -> {
                val remoteReference = metadata.remoteLastModified ?: metadata.lastSyncedAt
                if (local.lastModified >= remoteReference) {
                    WebDavSyncAction(path, WebDavSyncDirection.UPLOAD, WebDavSyncReason.LOCAL_NEWER)
                } else {
                    WebDavSyncAction(path, WebDavSyncDirection.DELETE_LOCAL, WebDavSyncReason.REMOTE_DELETED)
                }
            }
        }

    private fun handleRemoteOnly(
        path: String,
        remote: RemoteWebDavFile,
        metadata: WebDavSyncMetadataEntity?,
    ): WebDavSyncAction =
        when {
            metadata == null -> {
                WebDavSyncAction(path, WebDavSyncDirection.DOWNLOAD, WebDavSyncReason.REMOTE_ONLY)
            }

            !changed(remote.lastModified, metadata.remoteLastModified) && remote.etag == metadata.etag -> {
                WebDavSyncAction(path, WebDavSyncDirection.DELETE_REMOTE, WebDavSyncReason.LOCAL_DELETED)
            }

            else -> {
                val localReference = metadata.localLastModified ?: metadata.lastSyncedAt
                if ((remote.lastModified ?: 0L) >= localReference) {
                    WebDavSyncAction(path, WebDavSyncDirection.DOWNLOAD, WebDavSyncReason.REMOTE_NEWER)
                } else {
                    WebDavSyncAction(path, WebDavSyncDirection.DELETE_REMOTE, WebDavSyncReason.LOCAL_DELETED)
                }
            }
        }

    private fun newerWins(
        path: String,
        localTimestamp: Long,
        remoteTimestamp: Long,
    ): WebDavSyncAction =
        if (abs(localTimestamp - remoteTimestamp) <= timestampToleranceMs) {
            WebDavSyncAction(path, WebDavSyncDirection.NONE, WebDavSyncReason.SAME_TIMESTAMP)
        } else if (localTimestamp > remoteTimestamp) {
            WebDavSyncAction(path, WebDavSyncDirection.UPLOAD, WebDavSyncReason.LOCAL_NEWER)
        } else {
            WebDavSyncAction(path, WebDavSyncDirection.DOWNLOAD, WebDavSyncReason.REMOTE_NEWER)
        }

    private fun changed(
        current: Long?,
        previous: Long?,
    ): Boolean =
        when {
            current == null && previous == null -> false
            current == null || previous == null -> true
            else -> abs(current - previous) > timestampToleranceMs
        }
}

internal fun WebDavSyncAction.toOutcome(): WebDavSyncOutcome =
    WebDavSyncOutcome(
        path = path,
        direction = direction,
        reason = reason,
    )
