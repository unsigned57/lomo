package com.lomo.data.local.dao

import com.lomo.data.local.entity.WebDavLocalChangeJournalEntity

interface WebDavLocalChangeJournalDao {
    suspend fun getAll(workspaceGeneration: String): List<WebDavLocalChangeJournalEntity>

    suspend fun upsert(entity: WebDavLocalChangeJournalEntity)

    suspend fun deleteByIds(
        ids: Collection<String>,
        workspaceGeneration: String,
    )

    suspend fun clearAll(workspaceGeneration: String)
}
