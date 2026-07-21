package com.lomo.data.local.dao

import com.lomo.data.local.entity.S3RemoteShardStateEntity

data class S3RemoteShardScheduleTelemetrySnapshot(
    val shardCount: Int,
    val oldestScanAt: Long?,
    val hasElevatedChangePressure: Int,
    val hasHighVerificationUncertainty: Int,
)

interface S3RemoteShardStateDao {
    suspend fun getAll(workspaceGeneration: String): List<S3RemoteShardStateEntity>

    suspend fun getByBucketId(
        bucketId: String,
        workspaceGeneration: String,
    ): S3RemoteShardStateEntity?

    suspend fun getByBucketIds(
        bucketIds: List<String>,
        workspaceGeneration: String,
    ): List<S3RemoteShardStateEntity>

    suspend fun getMostSpecificAncestor(
        relativePrefix: String,
        workspaceGeneration: String,
    ): S3RemoteShardStateEntity?

    suspend fun getScheduleTelemetry(
        workspaceGeneration: String,
        now: Long,
        recentChangeWindowMs: Long,
        uncertaintyWindowMs: Long,
        changePressureThreshold: Double,
        verificationFailureThreshold: Double,
        minUncertaintyAttempts: Int,
        minUncertaintyFailures: Int,
    ): S3RemoteShardScheduleTelemetrySnapshot

    suspend fun upsertAll(entities: List<S3RemoteShardStateEntity>)

    suspend fun clearAll(workspaceGeneration: String)
}
