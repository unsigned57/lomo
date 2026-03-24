package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lomo.data.local.entity.MemoFtsEntity

@Dao
interface MemoFtsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemoFtsInternal(fts: MemoFtsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemoFtsBatchInternal(entries: List<MemoFtsEntity>)

    @Transaction
    suspend fun insertMemoFts(fts: MemoFtsEntity) {
        deleteMemoFts(fts.memoId)
        insertMemoFtsInternal(fts)
    }

    @Transaction
    suspend fun replaceMemoFtsBatch(entries: List<MemoFtsEntity>) {
        if (entries.isEmpty()) return
        deleteMemoFtsByIds(entries.map { it.memoId })
        insertMemoFtsBatchInternal(entries)
    }

    @Query("DELETE FROM lomo_fts WHERE memoId = :memoId")
    suspend fun deleteMemoFts(memoId: String)

    @Query("DELETE FROM lomo_fts WHERE memoId IN (:memoIds)")
    suspend fun deleteMemoFtsByIds(memoIds: List<String>)

    @Query("DELETE FROM lomo_fts")
    suspend fun clearFts()
}
