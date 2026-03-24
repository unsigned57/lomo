package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lomo.data.local.entity.MemoEntity

@Dao
interface MemoWriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemos(memos: List<MemoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemo(memo: MemoEntity)

    @Delete
    suspend fun deleteMemo(memo: MemoEntity)

    @Query("DELETE FROM Lomo WHERE id = :id")
    suspend fun deleteMemoById(id: String)

    @Query("DELETE FROM Lomo WHERE id IN (:ids)")
    suspend fun deleteMemosByIds(ids: List<String>)

    @Query("DELETE FROM Lomo")
    suspend fun clearAll()

    @Query("DELETE FROM Lomo WHERE id NOT IN (:ids)")
    suspend fun deleteMemosNotIn(ids: List<String>)

    @Query("DELETE FROM Lomo WHERE date = :date")
    suspend fun deleteMemosByDate(date: String)
}
