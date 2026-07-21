package com.lomo.data.local.dao

import com.lomo.data.local.entity.WebDavLocalFingerprintEntity

interface WebDavLocalFingerprintDao {
    suspend fun getByPath(
        path: String,
        workspaceGeneration: String,
    ): WebDavLocalFingerprintEntity?

    suspend fun upsert(entity: WebDavLocalFingerprintEntity)

    suspend fun clearAll(workspaceGeneration: String)

    suspend fun deleteByExcludedPaths(
        paths: Collection<String>,
        workspaceGeneration: String,
    )

    suspend fun deleteExcept(
        paths: Collection<String>,
        workspaceGeneration: String,
    ) {
        if (paths.isEmpty()) {
            clearAll(workspaceGeneration)
        } else {
            deleteByExcludedPaths(paths = paths, workspaceGeneration = workspaceGeneration)
        }
    }
}
