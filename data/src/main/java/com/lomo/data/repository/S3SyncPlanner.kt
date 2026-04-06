package com.lomo.data.repository

import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.domain.model.S3RemoteVerificationLevel
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncOutcome
import com.lomo.domain.model.S3SyncReason
import kotlin.math.abs

data class LocalS3File(
    val path: String,
    val lastModified: Long,
)

data class RemoteS3File(
    val path: String,
    val etag: String?,
    val lastModified: Long?,
    val remotePath: String = path,
    val verificationLevel: S3RemoteVerificationLevel = S3RemoteVerificationLevel.VERIFIED_REMOTE,
)

val RemoteS3File.verified: Boolean
    get() = verificationLevel == S3RemoteVerificationLevel.VERIFIED_REMOTE

val RemoteS3File.cached: Boolean
    get() = verificationLevel == S3RemoteVerificationLevel.INDEX_CACHED_REMOTE

data class S3SyncAction(
    val path: String,
    val direction: S3SyncDirection,
    val reason: S3SyncReason,
)

data class S3SyncPlan(
    val actions: List<S3SyncAction>,
    val pendingChanges: Int,
)

class S3SyncPlanner(
    private val timestampToleranceMs: Long = 1000L,
) {
    fun plan(
        localFiles: Map<String, LocalS3File>,
        remoteFiles: Map<String, RemoteS3File>,
        metadata: Map<String, S3SyncMetadataEntity>,
        missingRemoteVerificationByPath: Map<String, S3RemoteVerificationLevel> = emptyMap(),
        defaultMissingRemoteVerification: S3RemoteVerificationLevel = S3RemoteVerificationLevel.VERIFIED_REMOTE,
    ): S3SyncPlan {
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
                            missingRemoteVerification =
                                missingRemoteVerificationByPath[path] ?: defaultMissingRemoteVerification,
                        )?.let(::add)
                    }
            }

        return S3SyncPlan(
            actions = actions,
            pendingChanges = actions.count { it.direction != S3SyncDirection.NONE },
        )
    }

    fun planPaths(
        paths: Collection<String>,
        localFiles: Map<String, LocalS3File>,
        remoteFiles: Map<String, RemoteS3File>,
        metadata: Map<String, S3SyncMetadataEntity>,
        missingRemoteVerificationByPath: Map<String, S3RemoteVerificationLevel> = emptyMap(),
        defaultMissingRemoteVerification: S3RemoteVerificationLevel = S3RemoteVerificationLevel.VERIFIED_REMOTE,
    ): S3SyncPlan {
        val actions =
            paths
                .asSequence()
                .distinct()
                .sorted()
                .mapNotNull { path ->
                    createAction(
                        path = path,
                        local = localFiles[path],
                        remote = remoteFiles[path],
                        metadata = metadata[path],
                        missingRemoteVerification =
                            missingRemoteVerificationByPath[path] ?: defaultMissingRemoteVerification,
                    )
                }.toList()
        return S3SyncPlan(
            actions = actions,
            pendingChanges = actions.count { it.direction != S3SyncDirection.NONE },
        )
    }

    private fun createAction(
        path: String,
        local: LocalS3File?,
        remote: RemoteS3File?,
        metadata: S3SyncMetadataEntity?,
        missingRemoteVerification: S3RemoteVerificationLevel,
    ): S3SyncAction? =
        when {
            local != null && remote != null -> handleBothPresent(path, local, remote, metadata)
            local != null -> handleLocalOnly(path, local, metadata, missingRemoteVerification)
            remote != null -> handleRemoteOnly(path, remote, metadata)
            else -> null
        }

    private fun handleBothPresent(
        path: String,
        local: LocalS3File,
        remote: RemoteS3File,
        metadata: S3SyncMetadataEntity?,
    ): S3SyncAction? {
        if (metadata == null) {
            return S3SyncAction(path, S3SyncDirection.CONFLICT, S3SyncReason.CONFLICT)
        }

        val localChanged = changed(local.lastModified, metadata.localLastModified)
        val remoteChanged = changed(remote.lastModified, metadata.remoteLastModified) || remote.etag != metadata.etag

        return when {
            !localChanged && !remoteChanged -> null
            localChanged && !remoteChanged ->
                S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.LOCAL_ONLY)

            !localChanged && remoteChanged ->
                S3SyncAction(path, S3SyncDirection.DOWNLOAD, S3SyncReason.REMOTE_ONLY)

            else -> S3SyncAction(path, S3SyncDirection.CONFLICT, S3SyncReason.CONFLICT)
        }
    }

    private fun handleLocalOnly(
        path: String,
        local: LocalS3File,
        metadata: S3SyncMetadataEntity?,
        missingRemoteVerification: S3RemoteVerificationLevel,
    ): S3SyncAction? =
        when {
            metadata == null -> S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.LOCAL_ONLY)
            !changed(local.lastModified, metadata.localLastModified) ->
                if (missingRemoteVerification == S3RemoteVerificationLevel.VERIFIED_REMOTE) {
                    S3SyncAction(path, S3SyncDirection.DELETE_LOCAL, S3SyncReason.REMOTE_DELETED)
                } else {
                    null
                }

            else -> {
                val remoteReference = metadata.remoteLastModified ?: metadata.lastSyncedAt
                if (local.lastModified >= remoteReference) {
                    S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.LOCAL_NEWER)
                } else if (missingRemoteVerification == S3RemoteVerificationLevel.VERIFIED_REMOTE) {
                    S3SyncAction(path, S3SyncDirection.DELETE_LOCAL, S3SyncReason.REMOTE_DELETED)
                } else {
                    null
                }
            }
        }

    private fun handleRemoteOnly(
        path: String,
        remote: RemoteS3File,
        metadata: S3SyncMetadataEntity?,
    ): S3SyncAction =
        when {
            metadata == null -> S3SyncAction(path, S3SyncDirection.DOWNLOAD, S3SyncReason.REMOTE_ONLY)
            !changed(remote.lastModified, metadata.remoteLastModified) && remote.etag == metadata.etag ->
                S3SyncAction(path, S3SyncDirection.DELETE_REMOTE, S3SyncReason.LOCAL_DELETED)

            else -> {
                val localReference = metadata.localLastModified ?: metadata.lastSyncedAt
                if ((remote.lastModified ?: 0L) >= localReference) {
                    S3SyncAction(path, S3SyncDirection.DOWNLOAD, S3SyncReason.REMOTE_NEWER)
                } else {
                    S3SyncAction(path, S3SyncDirection.DELETE_REMOTE, S3SyncReason.LOCAL_DELETED)
                }
            }
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

internal fun S3SyncAction.toOutcome(): S3SyncOutcome =
    S3SyncOutcome(
        path = path,
        direction = direction,
        reason = reason,
    )
