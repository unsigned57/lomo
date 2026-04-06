package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lomo.data.local.entity.PendingSyncConflictEntity

@Dao
interface PendingSyncConflictDao {
    @Query("SELECT * FROM pending_sync_conflict WHERE backend = :backend LIMIT 1")
    suspend fun getByBackend(backend: String): PendingSyncConflictEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingSyncConflictEntity)

    @Query("DELETE FROM pending_sync_conflict WHERE backend = :backend")
    suspend fun deleteByBackend(backend: String)
}
