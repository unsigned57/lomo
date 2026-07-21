package com.lomo.data.local.dao

import com.lomo.data.local.entity.PendingSyncReviewEntity

interface PendingSyncReviewDao {
    suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncReviewEntity?

    suspend fun upsert(entity: PendingSyncReviewEntity)

    suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    )
}
