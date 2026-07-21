package com.lomo.data.local.dao

import com.lomo.data.local.entity.S3LocalChangeJournalEntity

interface S3LocalChangeJournalDao {
    suspend fun getAll(workspaceGeneration: String): List<S3LocalChangeJournalEntity>

    suspend fun upsert(entity: S3LocalChangeJournalEntity)

    suspend fun deleteByIds(
        ids: Collection<String>,
        workspaceGeneration: String,
    )

    suspend fun clearAll(workspaceGeneration: String)
}
