package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lomo.data.local.entity.S3SyncMetadataEntity

@Dao
interface S3SyncMetadataDao {
    @Query("SELECT * FROM s3_sync_metadata")
    suspend fun getAll(): List<S3SyncMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<S3SyncMetadataEntity>)

    @Query("DELETE FROM s3_sync_metadata WHERE relative_path = :relativePath")
    suspend fun deleteByRelativePath(relativePath: String)

    @Query("DELETE FROM s3_sync_metadata WHERE relative_path IN (:relativePaths)")
    suspend fun deleteByRelativePaths(relativePaths: List<String>)

    @Query("DELETE FROM s3_sync_metadata")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(entities: List<S3SyncMetadataEntity>) {
        clearAll()
        upsertAll(entities)
    }
}
