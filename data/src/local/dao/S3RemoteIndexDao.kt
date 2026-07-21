package com.lomo.data.local.dao

import com.lomo.data.local.entity.S3RemoteIndexEntity

interface S3RemoteIndexDao {
    suspend fun getAll(workspaceGeneration: String): List<S3RemoteIndexEntity>

    suspend fun getAllRelativePaths(workspaceGeneration: String): List<String>

    suspend fun getPresentCount(workspaceGeneration: String): Int

    suspend fun getByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity>

    suspend fun getByRelativePrefix(
        relativePrefix: String,
        descendantPattern: String,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity>

    suspend fun getOutsideScanBuckets(
        excludedBuckets: List<String>,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity>

    suspend fun getReconcileCandidates(
        limit: Int,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity>

    suspend fun upsertAll(entities: List<S3RemoteIndexEntity>)

    suspend fun deleteByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    )

    suspend fun deleteOutsideScanEpoch(
        scanEpoch: Long,
        workspaceGeneration: String,
    )

    suspend fun clearAll(workspaceGeneration: String)
}
