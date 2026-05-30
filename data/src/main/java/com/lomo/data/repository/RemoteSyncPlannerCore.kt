package com.lomo.data.repository

import kotlin.math.abs

data class RemoteSyncLocalSnapshot(
    val path: String,
    val lastModified: Long,
    val size: Long? = null,
    val localFingerprint: String? = null,
)

data class RemoteSyncRemoteSnapshot(
    val path: String,
    val etag: String?,
    val lastModified: Long?,
    val size: Long? = null,
    val contentFingerprint: String? = null,
)

data class RemoteSyncMetadataSnapshot(
    val path: String,
    val etag: String?,
    val remoteLastModified: Long?,
    val localLastModified: Long?,
    val localFingerprint: String? = null,
    val lastSyncedAt: Long,
)

enum class RemoteSyncRemoteAbsenceVerification {
    VERIFIED_ABSENT,
    UNVERIFIED_ABSENT,
}

enum class RemoteSyncDirection {
    NONE,
    UPLOAD,
    DOWNLOAD,
    DELETE_LOCAL,
    DELETE_REMOTE,
    CONFLICT,
}

enum class RemoteSyncReason {
    UNCHANGED,
    LOCAL_ONLY,
    REMOTE_ONLY,
    LOCAL_NEWER,
    REMOTE_NEWER,
    LOCAL_DELETED,
    REMOTE_DELETED,
    SAME_TIMESTAMP,
    CONFLICT,
}

data class RemoteSyncAction(
    val path: String,
    val direction: RemoteSyncDirection,
    val reason: RemoteSyncReason,
)

data class RemoteSyncPlan(
    val actions: List<RemoteSyncAction>,
    val pendingChanges: Int,
)

internal class RemoteSyncChangeComparator(
    private val timestampToleranceMs: Long,
) {
    fun changed(
        current: Long?,
        previous: Long?,
    ): Boolean =
        when {
            current == null && previous == null -> false
            current == null || previous == null -> true
            else -> abs(current - previous) > timestampToleranceMs
        }
}

internal interface RemoteSyncPlannerPolicy {
    fun localChanged(
        local: RemoteSyncLocalSnapshot,
        metadata: RemoteSyncMetadataSnapshot,
        comparator: RemoteSyncChangeComparator,
    ): Boolean

    fun remoteChanged(
        remote: RemoteSyncRemoteSnapshot,
        metadata: RemoteSyncMetadataSnapshot,
        comparator: RemoteSyncChangeComparator,
    ): Boolean

    fun actionForLocalOnly(
        path: String,
        local: RemoteSyncLocalSnapshot,
        metadata: RemoteSyncMetadataSnapshot?,
        comparator: RemoteSyncChangeComparator,
    ): RemoteSyncAction? =
        when {
            metadata == null -> RemoteSyncAction(path, RemoteSyncDirection.UPLOAD, RemoteSyncReason.LOCAL_ONLY)
            !localChanged(local, metadata, comparator) ->
                RemoteSyncAction(path, RemoteSyncDirection.DELETE_LOCAL, RemoteSyncReason.REMOTE_DELETED)

            else -> RemoteSyncAction(path, RemoteSyncDirection.CONFLICT, RemoteSyncReason.CONFLICT)
        }

    fun actionForRemoteOnly(
        path: String,
        remote: RemoteSyncRemoteSnapshot,
        metadata: RemoteSyncMetadataSnapshot?,
        comparator: RemoteSyncChangeComparator,
    ): RemoteSyncAction? =
        when {
            metadata == null -> RemoteSyncAction(path, RemoteSyncDirection.DOWNLOAD, RemoteSyncReason.REMOTE_ONLY)
            !remoteChanged(remote, metadata, comparator) ->
                RemoteSyncAction(path, RemoteSyncDirection.DELETE_REMOTE, RemoteSyncReason.LOCAL_DELETED)

            else -> RemoteSyncAction(path, RemoteSyncDirection.CONFLICT, RemoteSyncReason.CONFLICT)
        }
}

internal class RemoteSyncPlannerCore(
    timestampToleranceMs: Long = 1000L,
    private val policy: RemoteSyncPlannerPolicy,
) {
    private val comparator = RemoteSyncChangeComparator(timestampToleranceMs)

    fun plan(
        localFiles: Map<String, RemoteSyncLocalSnapshot>,
        remoteFiles: Map<String, RemoteSyncRemoteSnapshot>,
        metadata: Map<String, RemoteSyncMetadataSnapshot>,
        preResolvedActionsByPath: Map<String, RemoteSyncAction> = emptyMap(),
        suppressedPaths: Set<String> = emptySet(),
    ): RemoteSyncPlan =
        planPaths(
            paths = localFiles.keys + remoteFiles.keys + metadata.keys,
            localFiles = localFiles,
            remoteFiles = remoteFiles,
            metadata = metadata,
            preResolvedActionsByPath = preResolvedActionsByPath,
            suppressedPaths = suppressedPaths,
        )

    fun planPaths(
        paths: Collection<String>,
        localFiles: Map<String, RemoteSyncLocalSnapshot>,
        remoteFiles: Map<String, RemoteSyncRemoteSnapshot>,
        metadata: Map<String, RemoteSyncMetadataSnapshot>,
        preResolvedActionsByPath: Map<String, RemoteSyncAction> = emptyMap(),
        suppressedPaths: Set<String> = emptySet(),
    ): RemoteSyncPlan {
        val actions =
            paths
                .asSequence()
                .distinct()
                .sorted()
                .mapNotNull { path ->
                    if (path in suppressedPaths) {
                        return@mapNotNull null
                    }
                    preResolvedActionsByPath[path]?.let { return@mapNotNull it }
                    createAction(
                        path = path,
                        local = localFiles[path],
                        remote = remoteFiles[path],
                        metadata = metadata[path],
                    )
                }.toList()
        return RemoteSyncPlan(
            actions = actions,
            pendingChanges = actions.count { it.direction != RemoteSyncDirection.NONE },
        )
    }

    private fun createAction(
        path: String,
        local: RemoteSyncLocalSnapshot?,
        remote: RemoteSyncRemoteSnapshot?,
        metadata: RemoteSyncMetadataSnapshot?,
    ): RemoteSyncAction? =
        when {
            local != null && remote != null -> handleBothPresent(path, local, remote, metadata)
            local != null -> policy.actionForLocalOnly(path, local, metadata, comparator)
            remote != null -> policy.actionForRemoteOnly(path, remote, metadata, comparator)
            else -> null
        }

    private fun handleBothPresent(
        path: String,
        local: RemoteSyncLocalSnapshot,
        remote: RemoteSyncRemoteSnapshot,
        metadata: RemoteSyncMetadataSnapshot?,
    ): RemoteSyncAction? {
        if (metadata == null) {
            return RemoteSyncAction(path, RemoteSyncDirection.CONFLICT, RemoteSyncReason.CONFLICT)
        }

        val localChanged = policy.localChanged(local, metadata, comparator)
        val remoteChanged = policy.remoteChanged(remote, metadata, comparator)

        return when {
            !localChanged && !remoteChanged -> null
            localChanged && !remoteChanged ->
                RemoteSyncAction(path, RemoteSyncDirection.UPLOAD, RemoteSyncReason.LOCAL_ONLY)
            !localChanged && remoteChanged ->
                RemoteSyncAction(path, RemoteSyncDirection.DOWNLOAD, RemoteSyncReason.REMOTE_ONLY)
            else -> RemoteSyncAction(path, RemoteSyncDirection.CONFLICT, RemoteSyncReason.CONFLICT)
        }
    }
}
