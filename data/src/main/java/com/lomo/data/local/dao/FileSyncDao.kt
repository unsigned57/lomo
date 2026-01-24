package com.lomo.data.local.dao

import androidx.room.*
import com.lomo.data.local.entity.FileSyncEntity

@Dao
interface FileSyncDao {
    @Query("SELECT * FROM file_sync_metadata")
    suspend fun getAllSyncMetadata(): List<FileSyncEntity>

    @Query("SELECT * FROM file_sync_metadata WHERE filename = :filename AND isTrash = :isTrash")
    suspend fun getSyncMetadata(
        filename: String,
        isTrash: Boolean,
    ): FileSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncMetadata(metadata: FileSyncEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncMetadata(metadata: List<FileSyncEntity>)

    @Query("DELETE FROM file_sync_metadata WHERE filename = :filename AND isTrash = :isTrash")
    suspend fun deleteSyncMetadata(
        filename: String,
        isTrash: Boolean,
    )

    @Query("DELETE FROM file_sync_metadata")
    suspend fun deleteAllSyncMetadata()
}
