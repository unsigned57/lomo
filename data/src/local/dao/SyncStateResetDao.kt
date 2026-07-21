package com.lomo.data.local.dao

interface SyncStateResetDao {
    suspend fun clearWebDavSyncMetadata()

    suspend fun clearWebDavLocalFingerprints()

    suspend fun clearWebDavLocalChangeJournal()

    suspend fun clearS3SyncMetadata()

    suspend fun clearS3LocalChangeJournal()

    suspend fun clearS3SyncProtocolState()

    suspend fun clearS3RemoteIndex()

    suspend fun clearS3RemoteShardState()

    suspend fun clearPendingSyncConflicts()

    suspend fun clearPendingSyncReviews()

    suspend fun clearWorkspaceScopedSyncStateAllGenerations() {
        clearWebDavSyncMetadata()
        clearWebDavLocalFingerprints()
        clearWebDavLocalChangeJournal()
        clearS3SyncMetadata()
        clearS3LocalChangeJournal()
        clearS3SyncProtocolState()
        clearS3RemoteIndex()
        clearS3RemoteShardState()
        clearPendingSyncConflicts()
        clearPendingSyncReviews()
    }
}
