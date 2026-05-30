package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lomo.data.local.entity.PendingSyncReviewEntity

@Dao
interface PendingSyncReviewDao {
    @Query(
        """
        SELECT * FROM pending_sync_review
        WHERE workspace_generation = :workspaceGeneration AND backend = :backend
        LIMIT 1
        """,
    )
    suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncReviewEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingSyncReviewEntity)

    @Query("DELETE FROM pending_sync_review WHERE workspace_generation = :workspaceGeneration AND backend = :backend")
    suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    )
}
