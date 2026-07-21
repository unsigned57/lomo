package com.lomo.data.local.dao

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

    suspend fun getLocalAuditPage(
        afterRelativePath: String?,
        limit: Int,
    ): List<S3SyncMetadataEntity> =
        getAll()
            .filter { entity -> afterRelativePath == null || entity.relativePath > afterRelativePath }
            .sortedBy(S3SyncMetadataEntity::relativePath)
            .take(limit)

    suspend fun upsertAll(entities: List<S3SyncMetadataEntity>)

    suspend fun deleteByRelativePath(relativePath: String)

    suspend fun deleteByRelativePaths(relativePaths: List<String>)

    suspend fun clearAll()

    suspend fun replaceAll(entities: List<S3SyncMetadataEntity>) {
        clearAll()
        upsertAll(entities)
    }
}

interface RawS3SyncMetadataDao {
    suspend fun getAll(workspaceGeneration: String): List<S3SyncMetadataEntity>

    suspend fun getAllPlannerMetadataSnapshots(workspaceGeneration: String): List<S3SyncPlannerMetadataSnapshot>

    suspend fun getAllRemoteMetadataSnapshots(workspaceGeneration: String): List<S3SyncRemoteMetadataSnapshot>

    suspend fun getByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ): List<S3SyncMetadataEntity>

    suspend fun getLocalAuditPage(
        afterRelativePath: String?,
        limit: Int,
        workspaceGeneration: String,
    ): List<S3SyncMetadataEntity>

    suspend fun upsertAll(entities: List<S3SyncMetadataEntity>)

    suspend fun deleteByRelativePath(
        relativePath: String,
        workspaceGeneration: String,
    )

    suspend fun deleteByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    )

    suspend fun clearAll(workspaceGeneration: String)

    suspend fun replaceAll(
        entities: List<S3SyncMetadataEntity>,
        workspaceGeneration: String,
    ) {
        clearAll(workspaceGeneration)
        upsertAll(entities)
    }
}
