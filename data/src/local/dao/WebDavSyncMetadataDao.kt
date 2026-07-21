package com.lomo.data.local.dao

import com.lomo.data.local.entity.WebDavSyncMetadataEntity

interface WebDavSyncMetadataDao {
    suspend fun getAll(): List<WebDavSyncMetadataEntity>

    suspend fun getByRelativePaths(relativePaths: List<String>): List<WebDavSyncMetadataEntity>

    suspend fun upsertAll(entities: List<WebDavSyncMetadataEntity>)

    suspend fun deleteByRelativePath(relativePath: String)

    suspend fun deleteByRelativePaths(relativePaths: List<String>)

    suspend fun clearAll()

    suspend fun replaceAll(entities: List<WebDavSyncMetadataEntity>) {
        clearAll()
        upsertAll(entities)
    }
}

interface RawWebDavSyncMetadataDao {
    suspend fun getAll(workspaceGeneration: String): List<WebDavSyncMetadataEntity>

    suspend fun getByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ): List<WebDavSyncMetadataEntity>

    suspend fun upsertAll(entities: List<WebDavSyncMetadataEntity>)

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
        entities: List<WebDavSyncMetadataEntity>,
        workspaceGeneration: String,
    ) {
        clearAll(workspaceGeneration)
        upsertAll(entities)
    }
}
