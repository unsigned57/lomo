package com.lomo.data.repository

class WebDavSyncPlanner(
    private val timestampToleranceMs: Long = 1000L,
) {
    private val core =
        RemoteSyncPlannerCore(
            timestampToleranceMs = timestampToleranceMs,
            policy = WebDavRemoteSyncPlannerPolicy,
        )

    fun plan(
        localFiles: Map<String, RemoteSyncLocalSnapshot>,
        remoteFiles: Map<String, RemoteSyncRemoteSnapshot>,
        metadata: Map<String, RemoteSyncMetadataSnapshot>,
        preResolvedActionsByPath: Map<String, RemoteSyncAction> = emptyMap(),
        suppressedPaths: Set<String> = emptySet(),
    ): RemoteSyncPlan =
        core.plan(
            localFiles = localFiles,
            remoteFiles = remoteFiles,
            metadata = metadata,
            preResolvedActionsByPath = preResolvedActionsByPath,
            suppressedPaths = suppressedPaths,
        )
}

private object WebDavRemoteSyncPlannerPolicy : RemoteSyncPlannerPolicy {
    override fun localChanged(
        local: RemoteSyncLocalSnapshot,
        metadata: RemoteSyncMetadataSnapshot,
        comparator: RemoteSyncChangeComparator,
    ): Boolean =
        when {
            local.localFingerprint != null && metadata.localFingerprint != null ->
                local.localFingerprint != metadata.localFingerprint

            else -> comparator.changed(local.lastModified, metadata.localLastModified)
        }

    override fun remoteChanged(
        remote: RemoteSyncRemoteSnapshot,
        metadata: RemoteSyncMetadataSnapshot,
        comparator: RemoteSyncChangeComparator,
    ): Boolean =
        when {
            remote.etag != null && metadata.etag != null -> remote.etag != metadata.etag
            else -> comparator.changed(remote.lastModified, metadata.remoteLastModified)
        }
}
