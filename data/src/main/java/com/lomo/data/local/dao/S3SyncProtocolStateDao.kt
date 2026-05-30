package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lomo.data.local.entity.S3SyncProtocolStateEntity

@Dao
interface S3SyncProtocolStateDao {
    @Query(
        """
        SELECT * FROM s3_sync_protocol_state
        WHERE workspace_generation = :workspaceGeneration AND id = :id
        """,
    )
    suspend fun getById(
        workspaceGeneration: String,
        id: Int = S3SyncProtocolStateEntity.SINGLETON_ID,
    ): S3SyncProtocolStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: S3SyncProtocolStateEntity)

    @Query("DELETE FROM s3_sync_protocol_state WHERE workspace_generation = :workspaceGeneration")
    suspend fun clearAll(workspaceGeneration: String)
}
