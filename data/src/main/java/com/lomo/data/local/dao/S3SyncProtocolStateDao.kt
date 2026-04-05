package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lomo.data.local.entity.S3SyncProtocolStateEntity

@Dao
interface S3SyncProtocolStateDao {
    @Query("SELECT * FROM s3_sync_protocol_state WHERE id = :id")
    suspend fun getById(id: Int = S3SyncProtocolStateEntity.SINGLETON_ID): S3SyncProtocolStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: S3SyncProtocolStateEntity)

    @Query("DELETE FROM s3_sync_protocol_state")
    suspend fun clearAll()
}
