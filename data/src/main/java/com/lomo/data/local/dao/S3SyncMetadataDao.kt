package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.lomo.data.local.entity.S3SyncMetadataEntity

data class S3SyncRemoteMetadataSnapshot(
    val relativePath: String,
    val remotePath: String,
    val etag: String?,
    val remoteLastModified: Long?,
)

data class S3SyncPlannerMetadataSnapshot(
    val relativePath: String,
    val remotePath: String,
    val etag: String?,
    val remoteLastModified: Long?,
    val localLastModified: Long?,
    val localSize: Long? = null,
    val remoteSize: Long? = null,
    val localFingerprint: String? = null,
    val lastSyncedAt: Long,
    val lastResolvedDirection: String,
    val lastResolvedReason: String,
)

interface S3SyncMetadataDao {
    suspend fun getAll(): List<S3SyncMetadataEntity>

    suspend fun getAllPlannerMetadataSnapshots(): List<S3SyncPlannerMetadataSnapshot>

    suspend fun getAllRemoteMetadataSnapshots(): List<S3SyncRemoteMetadataSnapshot>

    suspend fun getByRelativePaths(relativePaths: List<String>): List<S3SyncMetadataEntity>

    suspend fun upsertAll(entities: List<S3SyncMetadataEntity>)

    suspend fun deleteByRelativePath(relativePath: String)

    suspend fun deleteByRelativePaths(relativePaths: List<String>)

    suspend fun clearAll()

    suspend fun replaceAll(entities: List<S3SyncMetadataEntity>) {
        clearAll()
        upsertAll(entities)
    }
}

@Dao
interface RawS3SyncMetadataDao {
    @Query("SELECT * FROM s3_sync_metadata WHERE workspace_generation = :workspaceGeneration")
    suspend fun getAll(workspaceGeneration: String): List<S3SyncMetadataEntity>

    @Query(
        """
        SELECT
            relative_path AS relativePath,
            remote_path AS remotePath,
            etag AS etag,
            remote_last_modified AS remoteLastModified,
            local_last_modified AS localLastModified,
            local_size AS localSize,
            remote_size AS remoteSize,
            local_fingerprint AS localFingerprint,
            last_synced_at AS lastSyncedAt,
            last_resolved_direction AS lastResolvedDirection,
            last_resolved_reason AS lastResolvedReason
        FROM s3_sync_metadata
        WHERE workspace_generation = :workspaceGeneration
        """,
    )
    suspend fun getAllPlannerMetadataSnapshots(workspaceGeneration: String): List<S3SyncPlannerMetadataSnapshot>

    @Query(
        """
        SELECT
            relative_path AS relativePath,
            remote_path AS remotePath,
            etag AS etag,
            remote_last_modified AS remoteLastModified
        FROM s3_sync_metadata
        WHERE workspace_generation = :workspaceGeneration
        """,
    )
    suspend fun getAllRemoteMetadataSnapshots(workspaceGeneration: String): List<S3SyncRemoteMetadataSnapshot>

    @Query(
        """
        SELECT * FROM s3_sync_metadata
        WHERE workspace_generation = :workspaceGeneration AND relative_path IN (:relativePaths)
        """,
    )
    suspend fun getByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ): List<S3SyncMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<S3SyncMetadataEntity>)

    @Query(
        """
        DELETE FROM s3_sync_metadata
        WHERE workspace_generation = :workspaceGeneration AND relative_path = :relativePath
        """,
    )
    suspend fun deleteByRelativePath(
        relativePath: String,
        workspaceGeneration: String,
    )

    @Query(
        """
        DELETE FROM s3_sync_metadata
        WHERE workspace_generation = :workspaceGeneration AND relative_path IN (:relativePaths)
        """,
    )
    suspend fun deleteByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    )

    @Query("DELETE FROM s3_sync_metadata WHERE workspace_generation = :workspaceGeneration")
    suspend fun clearAll(workspaceGeneration: String)

    @Transaction
    suspend fun replaceAll(
        entities: List<S3SyncMetadataEntity>,
        workspaceGeneration: String,
    ) {
        clearAll(workspaceGeneration)
        upsertAll(entities)
    }
}
