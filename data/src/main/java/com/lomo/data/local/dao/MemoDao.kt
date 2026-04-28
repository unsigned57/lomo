package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Query
import com.lomo.data.local.entity.MemoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoDao {
    @Query("SELECT * FROM Lomo ORDER BY timestamp DESC, id DESC")
    fun getAllMemosFlow(): Flow<List<MemoEntity>>

    @Query("SELECT * FROM Lomo ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomMemosDirect(limit: Int): List<MemoEntity>

    suspend fun getRandomMemos(limit: Int): List<MemoEntity> {
        if (limit <= 0) return emptyList()
        return getRandomMemosDirect(limit)
    }

    @Query("SELECT * FROM Lomo ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMemos(limit: Int): List<MemoEntity>

    @Query("SELECT id FROM Lomo")
    suspend fun getAllMemoIds(): List<String>

    @Query("SELECT * FROM Lomo WHERE id IN (:ids)")
    suspend fun getMemosByIds(ids: List<String>): List<MemoEntity>

    @Query("SELECT COUNT(*) FROM Lomo")
    suspend fun getMemoCountSync(): Int

    @Query("SELECT * FROM Lomo WHERE id = :id")
    suspend fun getMemo(id: String): MemoEntity?

    @Query("SELECT * FROM Lomo")
    suspend fun getAllMemosSync(): List<MemoEntity>

    @Query("SELECT * FROM Lomo WHERE date = :date")
    suspend fun getMemosByDate(date: String): List<MemoEntity>

    @Query("SELECT * FROM Lomo WHERE date IN (:dates)")
    suspend fun getMemosByDates(dates: List<String>): List<MemoEntity>
}
