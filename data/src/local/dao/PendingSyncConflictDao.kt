package com.lomo.data.local.dao

import com.lomo.data.local.entity.PendingSyncConflictEntity

interface PendingSyncConflictDao {
    suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncConflictEntity?

    suspend fun upsert(entity: PendingSyncConflictEntity)

    suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    )
}
