package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lomo.data.local.entity.TrashMemoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoTrashDao {
    @Query("SELECT * FROM LomoTrash ORDER BY timestamp DESC, id DESC")
    fun getDeletedMemosFlow(): Flow<List<TrashMemoEntity>>

    @Query("SELECT * FROM LomoTrash ORDER BY timestamp DESC, id DESC")
    suspend fun getDeletedMemos(): List<TrashMemoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrashMemos(memos: List<TrashMemoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrashMemo(memo: TrashMemoEntity)

    @Query("SELECT * FROM LomoTrash WHERE id = :id")
    suspend fun getTrashMemo(id: String): TrashMemoEntity?

    @Query("SELECT * FROM LomoTrash WHERE date = :date")
    suspend fun getTrashMemosByDate(date: String): List<TrashMemoEntity>

    @Query("DELETE FROM LomoTrash WHERE id = :id")
    suspend fun deleteTrashMemoById(id: String)

    @Query("DELETE FROM LomoTrash WHERE id IN (:ids)")
    suspend fun deleteTrashMemosByIds(ids: List<String>)

    @Query("DELETE FROM LomoTrash WHERE date = :date")
    suspend fun deleteTrashMemosByDate(date: String)

    @Query("DELETE FROM LomoTrash")
    suspend fun clearTrash()
}
