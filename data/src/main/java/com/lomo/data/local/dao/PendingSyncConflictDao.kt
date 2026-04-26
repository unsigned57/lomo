package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
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
