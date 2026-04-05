package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lomo.data.local.entity.S3SyncMetadataEntity

data class S3SyncRemoteMetadataSnapshot(
    val relativePath: String,
    val remotePath: String,
    val etag: String?,
    val remoteLastModified: Long?,
)

@Dao
interface S3SyncMetadataDao {
    @Query("SELECT * FROM s3_sync_metadata")
    suspend fun getAll(): List<S3SyncMetadataEntity>

    @Query(
        """
        SELECT
            relative_path AS relativePath,
            remote_path AS remotePath,
            etag AS etag,
            remote_last_modified AS remoteLastModified
        FROM s3_sync_metadata
        """,
    )
    suspend fun getAllRemoteMetadataSnapshots(): List<S3SyncRemoteMetadataSnapshot>

    @Query("SELECT * FROM s3_sync_metadata WHERE relative_path IN (:relativePaths)")
    suspend fun getByRelativePaths(relativePaths: List<String>): List<S3SyncMetadataEntity>

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
