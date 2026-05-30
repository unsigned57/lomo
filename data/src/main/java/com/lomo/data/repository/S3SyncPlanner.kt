package com.lomo.data.repository

class S3SyncPlanner(
    internal val timestampToleranceMs: Long = 1000L,
) {
    fun plan(
        localFiles: Map<String, RemoteSyncLocalSnapshot>,
        remoteFiles: Map<String, RemoteSyncRemoteSnapshot>,
        metadata: Map<String, RemoteSyncMetadataSnapshot>,
        preResolvedActionsByPath: Map<String, RemoteSyncAction> = emptyMap(),
        suppressedPaths: Set<String> = emptySet(),
        missingRemoteVerificationByPath: Map<String, RemoteSyncRemoteAbsenceVerification> = emptyMap(),
        defaultMissingRemoteVerification: RemoteSyncRemoteAbsenceVerification =
            RemoteSyncRemoteAbsenceVerification.VERIFIED_ABSENT,
    ): RemoteSyncPlan =
        planPaths(
            paths = localFiles.keys + remoteFiles.keys + metadata.keys,
            localFiles = localFiles,
            remoteFiles = remoteFiles,
            metadata = metadata,
            preResolvedActionsByPath = preResolvedActionsByPath,
            suppressedPaths = suppressedPaths,
            missingRemoteVerificationByPath = missingRemoteVerificationByPath,
            defaultMissingRemoteVerification = defaultMissingRemoteVerification,
        )

    fun planPaths(
        paths: Collection<String>,
        localFiles: Map<String, RemoteSyncLocalSnapshot>,
        remoteFiles: Map<String, RemoteSyncRemoteSnapshot>,
        metadata: Map<String, RemoteSyncMetadataSnapshot>,
        preResolvedActionsByPath: Map<String, RemoteSyncAction> = emptyMap(),
        suppressedPaths: Set<String> = emptySet(),
        missingRemoteVerificationByPath: Map<String, RemoteSyncRemoteAbsenceVerification> = emptyMap(),
        defaultMissingRemoteVerification: RemoteSyncRemoteAbsenceVerification =
            RemoteSyncRemoteAbsenceVerification.VERIFIED_ABSENT,
    ): RemoteSyncPlan {
        val policy =
            S3RemoteSyncPlannerPolicy(
                missingRemoteVerificationByPath = missingRemoteVerificationByPath,
                defaultMissingRemoteVerification = defaultMissingRemoteVerification,
            )
        return RemoteSyncPlannerCore(
            timestampToleranceMs = timestampToleranceMs,
            policy = policy,
        ).planPaths(
            paths = paths,
            localFiles = localFiles,
            remoteFiles = remoteFiles,
            metadata = metadata,
            preResolvedActionsByPath = preResolvedActionsByPath,
            suppressedPaths = suppressedPaths,
        )
    }
}

private class S3RemoteSyncPlannerPolicy(
    private val missingRemoteVerificationByPath: Map<String, RemoteSyncRemoteAbsenceVerification>,
    private val defaultMissingRemoteVerification: RemoteSyncRemoteAbsenceVerification,
) : RemoteSyncPlannerPolicy {
    override fun localChanged(
        local: RemoteSyncLocalSnapshot,
        metadata: RemoteSyncMetadataSnapshot,
        comparator: RemoteSyncChangeComparator,
    ): Boolean = comparator.changed(local.lastModified, metadata.localLastModified)

    override fun remoteChanged(
        remote: RemoteSyncRemoteSnapshot,
        metadata: RemoteSyncMetadataSnapshot,
        comparator: RemoteSyncChangeComparator,
    ): Boolean = comparator.changed(remote.lastModified, metadata.remoteLastModified) || remote.etag != metadata.etag

    override fun actionForLocalOnly(
        path: String,
        local: RemoteSyncLocalSnapshot,
        metadata: RemoteSyncMetadataSnapshot?,
        comparator: RemoteSyncChangeComparator,
    ): RemoteSyncAction? =
        when {
            metadata == null -> RemoteSyncAction(path, RemoteSyncDirection.UPLOAD, RemoteSyncReason.LOCAL_ONLY)
            !localChanged(local, metadata, comparator) -> verifiedRemoteDeleteAction(path)
            else -> localOnlyChangedAction(path, local, metadata)
        }

    override fun actionForRemoteOnly(
        path: String,
        remote: RemoteSyncRemoteSnapshot,
        metadata: RemoteSyncMetadataSnapshot?,
        comparator: RemoteSyncChangeComparator,
    ): RemoteSyncAction? =
        when {
            metadata == null -> RemoteSyncAction(path, RemoteSyncDirection.DOWNLOAD, RemoteSyncReason.REMOTE_ONLY)
            !remoteChanged(remote, metadata, comparator) ->
                RemoteSyncAction(path, RemoteSyncDirection.DELETE_REMOTE, RemoteSyncReason.LOCAL_DELETED)

            else -> {
                val localReference = metadata.localLastModified ?: metadata.lastSyncedAt
                if ((remote.lastModified ?: 0L) >= localReference) {
                    RemoteSyncAction(path, RemoteSyncDirection.DOWNLOAD, RemoteSyncReason.REMOTE_NEWER)
                } else {
                    RemoteSyncAction(path, RemoteSyncDirection.DELETE_REMOTE, RemoteSyncReason.LOCAL_DELETED)
                }
            }
        }

    private fun localOnlyChangedAction(
        path: String,
        local: RemoteSyncLocalSnapshot,
        metadata: RemoteSyncMetadataSnapshot,
    ): RemoteSyncAction? {
        val remoteReference = metadata.remoteLastModified ?: metadata.lastSyncedAt
        return if (local.lastModified >= remoteReference) {
            RemoteSyncAction(path, RemoteSyncDirection.UPLOAD, RemoteSyncReason.LOCAL_NEWER)
        } else {
            verifiedRemoteDeleteAction(path)
        }
    }

    private fun verifiedRemoteDeleteAction(path: String): RemoteSyncAction? =
        if ((missingRemoteVerificationByPath[path] ?: defaultMissingRemoteVerification) ==
            RemoteSyncRemoteAbsenceVerification.VERIFIED_ABSENT
        ) {
            RemoteSyncAction(path, RemoteSyncDirection.DELETE_LOCAL, RemoteSyncReason.REMOTE_DELETED)
        } else {
            null
        }
}
