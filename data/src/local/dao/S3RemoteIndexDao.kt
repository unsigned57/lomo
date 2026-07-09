package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lomo.data.local.entity.S3RemoteIndexEntity

@Dao
interface S3RemoteIndexDao {
    @Query("SELECT * FROM s3_remote_index WHERE workspace_generation = :workspaceGeneration")
    suspend fun getAll(workspaceGeneration: String): List<S3RemoteIndexEntity>

    @Query("SELECT relative_path FROM s3_remote_index WHERE workspace_generation = :workspaceGeneration")
    suspend fun getAllRelativePaths(workspaceGeneration: String): List<String>

    @Query(
        """
        SELECT COUNT(*) FROM s3_remote_index
        WHERE workspace_generation = :workspaceGeneration AND missing_on_last_scan = 0
        """,
    )
    suspend fun getPresentCount(workspaceGeneration: String): Int

    @Query(
        """
        SELECT * FROM s3_remote_index
        WHERE workspace_generation = :workspaceGeneration AND relative_path IN (:relativePaths)
        """,
    )
    suspend fun getByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity>

    @Query(
        """
        SELECT * FROM s3_remote_index
        WHERE workspace_generation = :workspaceGeneration
            AND (relative_path = :relativePrefix OR relative_path LIKE :descendantPattern)
        """,
    )
    suspend fun getByRelativePrefix(
        relativePrefix: String,
        descendantPattern: String,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity>

    @Query(
        """
        SELECT * FROM s3_remote_index
        WHERE workspace_generation = :workspaceGeneration AND scan_bucket NOT IN (:excludedBuckets)
        """,
    )
    suspend fun getOutsideScanBuckets(
        excludedBuckets: List<String>,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity>

    @Query(
        """
        SELECT * FROM s3_remote_index
        WHERE workspace_generation = :workspaceGeneration
        ORDER BY dirty_suspect DESC, missing_on_last_scan DESC, scan_priority DESC, COALESCE(last_verified_at, 0) ASC, last_seen_at ASC
        LIMIT :limit
        """,
    )
    suspend fun getReconcileCandidates(
        limit: Int,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<S3RemoteIndexEntity>)

    @Query(
        """
        DELETE FROM s3_remote_index
        WHERE workspace_generation = :workspaceGeneration AND relative_path IN (:relativePaths)
        """,
    )
    suspend fun deleteByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    )

    @Query("DELETE FROM s3_remote_index WHERE workspace_generation = :workspaceGeneration AND scan_epoch != :scanEpoch")
    suspend fun deleteOutsideScanEpoch(
        scanEpoch: Long,
        workspaceGeneration: String,
    )

    @Query("DELETE FROM s3_remote_index WHERE workspace_generation = :workspaceGeneration")
    suspend fun clearAll(workspaceGeneration: String)
}
