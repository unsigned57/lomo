package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lomo.data.local.entity.S3RemoteIndexEntity

@Dao
interface S3RemoteIndexDao {
    @Query("SELECT * FROM s3_remote_index")
    suspend fun getAll(): List<S3RemoteIndexEntity>

    @Query("SELECT COUNT(*) FROM s3_remote_index WHERE missing_on_last_scan = 0")
    suspend fun getPresentCount(): Int

    @Query("SELECT * FROM s3_remote_index WHERE relative_path IN (:relativePaths)")
    suspend fun getByRelativePaths(relativePaths: List<String>): List<S3RemoteIndexEntity>

    @Query(
        """
        SELECT * FROM s3_remote_index
        WHERE relative_path = :relativePrefix OR relative_path LIKE :descendantPattern
        """,
    )
    suspend fun getByRelativePrefix(
        relativePrefix: String,
        descendantPattern: String,
    ): List<S3RemoteIndexEntity>

    @Query(
        """
        SELECT * FROM s3_remote_index
        ORDER BY dirty_suspect DESC, missing_on_last_scan DESC, scan_priority DESC, COALESCE(last_verified_at, 0) ASC, last_seen_at ASC
        LIMIT :limit
        """,
    )
    suspend fun getReconcileCandidates(limit: Int): List<S3RemoteIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<S3RemoteIndexEntity>)

    @Query("DELETE FROM s3_remote_index WHERE relative_path IN (:relativePaths)")
    suspend fun deleteByRelativePaths(relativePaths: List<String>)

    @Query("DELETE FROM s3_remote_index WHERE scan_epoch != :scanEpoch")
    suspend fun deleteOutsideScanEpoch(scanEpoch: Long)

    @Query("DELETE FROM s3_remote_index")
    suspend fun clearAll()
}
