package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
interface SyncStateResetDao {
    @Query("DELETE FROM webdav_sync_metadata")
    suspend fun clearWebDavSyncMetadata()

    @Query("DELETE FROM webdav_local_fingerprint")
    suspend fun clearWebDavLocalFingerprints()

    @Query("DELETE FROM webdav_local_change_journal")
    suspend fun clearWebDavLocalChangeJournal()

    @Query("DELETE FROM s3_sync_metadata")
    suspend fun clearS3SyncMetadata()

    @Query("DELETE FROM s3_local_change_journal")
    suspend fun clearS3LocalChangeJournal()

    @Query("DELETE FROM s3_sync_protocol_state")
    suspend fun clearS3SyncProtocolState()

    @Query("DELETE FROM s3_remote_index")
    suspend fun clearS3RemoteIndex()

    @Query("DELETE FROM s3_remote_shard_state")
    suspend fun clearS3RemoteShardState()

    @Query("DELETE FROM pending_sync_conflict")
    suspend fun clearPendingSyncConflicts()

    @Query("DELETE FROM pending_sync_review")
    suspend fun clearPendingSyncReviews()

    @Transaction
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
