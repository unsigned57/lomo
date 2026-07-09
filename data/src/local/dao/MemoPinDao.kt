package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lomo.data.local.entity.MemoPinEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoPinDao {
    @Query("SELECT memoId FROM MemoPin")
    fun getPinnedMemoIdsFlow(): Flow<List<String>>

    @Query("SELECT memoId FROM MemoPin")
    suspend fun getPinnedMemoIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemoPin(pin: MemoPinEntity)

    @Query("DELETE FROM MemoPin WHERE memoId = :memoId")
    suspend fun deleteMemoPin(memoId: String)
}
