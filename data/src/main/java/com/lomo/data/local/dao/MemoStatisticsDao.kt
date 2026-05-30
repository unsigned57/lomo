package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoStatisticsDao {
    @Query(
        """
        SELECT tag AS name, COUNT(DISTINCT memoId) AS count
        FROM MemoTagCrossRef
        GROUP BY tag
        ORDER BY tag COLLATE NOCASE
        """,
    )
    fun getTagCountsFlow(): Flow<List<TagCountRow>>

    @Query(
        """
        SELECT tag AS name, COUNT(DISTINCT memoId) AS count
        FROM MemoTagCrossRef
        GROUP BY tag
        ORDER BY tag COLLATE NOCASE
        """,
    )
    suspend fun getTagCounts(): List<TagCountRow>

    @Query("SELECT COUNT(*) FROM Lomo")
    fun getMemoCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT date) FROM Lomo")
    fun getActiveDayCount(): Flow<Int>

    @Query("SELECT timestamp FROM Lomo ORDER BY timestamp DESC")
    fun getAllTimestamps(): Flow<List<Long>>

    @Query(
        """
        SELECT date, COUNT(*) AS count
        FROM Lomo
        GROUP BY date
        """,
    )
    fun getMemoCountByDateFlow(): Flow<List<DateCountRow>>

    @Query("SELECT timestamp, statisticsWordCount, statisticsCharacterCount FROM Lomo ORDER BY timestamp DESC")
    suspend fun getMemoStatisticsProjection(): List<MemoStatisticsProjectionRow>

    @Query(
        """
        SELECT COUNT(*)
        FROM MemoImageAttachment
        WHERE imagePath = :imagePath
          AND memoId != :excludeId
        """,
    )
    suspend fun countMemosAndTrashWithImage(
        imagePath: String,
        excludeId: String,
    ): Int
}

data class TagCountRow(
    val name: String,
    val count: Int,
)

data class DateCountRow(
    val date: String,
    val count: Int,
)

data class MemoStatisticsProjectionRow(
    val timestamp: Long,
    val statisticsWordCount: Int,
    val statisticsCharacterCount: Int,
)
