package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lomo.data.local.entity.WebDavLocalChangeJournalEntity

@Dao
interface WebDavLocalChangeJournalDao {
    @Query(
        """
        SELECT * FROM webdav_local_change_journal
        WHERE workspace_generation = :workspaceGeneration
        ORDER BY id ASC
        """,
    )
    suspend fun getAll(workspaceGeneration: String): List<WebDavLocalChangeJournalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WebDavLocalChangeJournalEntity)

    @Query(
        """
        DELETE FROM webdav_local_change_journal
        WHERE workspace_generation = :workspaceGeneration AND id IN (:ids)
        """,
    )
    suspend fun deleteByIds(
        ids: Collection<String>,
        workspaceGeneration: String,
    )

    @Query("DELETE FROM webdav_local_change_journal WHERE workspace_generation = :workspaceGeneration")
    suspend fun clearAll(workspaceGeneration: String)
}
