package com.lomo.data.repository

internal class S3RemoteRecentActivityTracker {
    suspend fun recordForegroundCandidates(
        remoteIndexStore: S3RemoteIndexStore,
        relativePaths: Collection<String>,
        now: Long = System.currentTimeMillis(),
        scanEpoch: Long = 0L,
    ) {
        promoteKnownRecentCandidates(
            remoteIndexStore = remoteIndexStore,
            relativePaths = relativePaths,
            now = now,
            scanEpoch = scanEpoch,
        )
    }

    suspend fun recordRetryCandidates(
        remoteIndexStore: S3RemoteIndexStore,
        relativePaths: Collection<String>,
        now: Long = System.currentTimeMillis(),
        scanEpoch: Long = 0L,
    ) {
        promoteKnownRecentCandidates(
            remoteIndexStore = remoteIndexStore,
            relativePaths = relativePaths,
            now = now,
            scanEpoch = scanEpoch,
        )
    }

    private suspend fun promoteKnownRecentCandidates(
        remoteIndexStore: S3RemoteIndexStore,
        relativePaths: Collection<String>,
        now: Long,
        scanEpoch: Long,
    ) {
        if (!remoteIndexStore.remoteIndexEnabled || relativePaths.isEmpty()) {
            return
        }
        val existingEntries = remoteIndexStore.readByRelativePaths(relativePaths)
        if (existingEntries.isEmpty()) {
            return
        }
        remoteIndexStore.upsert(existingEntries.map { entry -> entry.promoteForRecentCandidate(now, scanEpoch) })
    }
}
