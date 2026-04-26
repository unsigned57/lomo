package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import com.lomo.data.local.entity.MemoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoSearchDao {
    @Query(
        """
        SELECT * FROM Lomo 
        WHERE content LIKE '%' || :query || '%' 
        ORDER BY timestamp DESC, id DESC
        """,
    )
    fun searchMemosFlow(query: String): Flow<List<MemoEntity>>

    @RawQuery(observedEntities = [MemoEntity::class])
    fun searchMemosByFtsRaw(query: RoomRawQuery): Flow<List<MemoEntity>>

    fun searchMemosByFtsFlow(matchQuery: String): Flow<List<MemoEntity>> =
        searchMemosByFtsRaw(
            RoomRawQuery(
                sql =
                    """
                    SELECT Lomo.* FROM Lomo
                    INNER JOIN lomo_fts ON lomo_fts.rowid = Lomo.rowid
                    WHERE lomo_fts MATCH ?
                    ORDER BY Lomo.timestamp DESC, Lomo.id DESC
                    """.trimIndent(),
                onBindStatement = { statement ->
                    statement.bindText(index = 1, value = matchQuery)
                },
            ),
        )

    @Query(
        """
        SELECT Lomo.*
        FROM Lomo
        INNER JOIN MemoTagCrossRef ON MemoTagCrossRef.memoId = Lomo.id
        WHERE MemoTagCrossRef.tag = :tag OR MemoTagCrossRef.tag LIKE :tagPrefix
        ORDER BY Lomo.timestamp DESC, Lomo.id DESC
        """,
    )
    fun getMemosByTagFlow(
        tag: String,
        tagPrefix: String,
    ): Flow<List<MemoEntity>>

    @Query("SELECT DISTINCT tag FROM MemoTagCrossRef ORDER BY tag COLLATE NOCASE")
    fun getAllTagsFlow(): Flow<List<String>>

    @Query(
        """
        SELECT tag AS name, COUNT(DISTINCT memoId) AS count
        FROM MemoTagCrossRef
        GROUP BY tag
        ORDER BY tag COLLATE NOCASE
        """,
    )
    fun getTagCountsFlow(): Flow<List<TagCountRow>>

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
