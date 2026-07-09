package com.lomo.data.local.dao

import androidx.paging.PagingSource
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Query
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import com.lomo.data.local.entity.MemoEntity
import kotlinx.coroutines.flow.Flow

@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
interface MemoSearchDao {
    @Query(
        """
        SELECT Lomo.*
        FROM Lomo
        WHERE EXISTS (
            SELECT 1
            FROM MemoTagCrossRef
            WHERE MemoTagCrossRef.memoId = Lomo.id
              AND (MemoTagCrossRef.tag = :tag OR MemoTagCrossRef.tag LIKE :tagPrefix)
        )
        ORDER BY Lomo.timestamp DESC, Lomo.id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getMemosByTagPage(
        tag: String,
        tagPrefix: String,
        limit: Int,
        offset: Int,
    ): List<MemoEntity>

    @Query(
        """
        SELECT Lomo.*
        FROM Lomo
        WHERE EXISTS (
            SELECT 1
            FROM MemoTagCrossRef
            WHERE MemoTagCrossRef.memoId = Lomo.id
              AND (MemoTagCrossRef.tag = :tag OR MemoTagCrossRef.tag LIKE :tagPrefix)
        )
        ORDER BY Lomo.timestamp DESC, Lomo.id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun getMemosByTagPageFlow(
        tag: String,
        tagPrefix: String,
        limit: Int,
        offset: Int,
    ): Flow<List<MemoEntity>>

    @Query(
        """
        SELECT Lomo.*, CASE WHEN MemoPin.memoId IS NULL THEN 0 ELSE 1 END AS isPinned
        FROM Lomo
        LEFT JOIN MemoPin ON Lomo.id = MemoPin.memoId
        WHERE EXISTS (
            SELECT 1
            FROM MemoTagCrossRef
            WHERE MemoTagCrossRef.memoId = Lomo.id
              AND (MemoTagCrossRef.tag = :tag OR MemoTagCrossRef.tag LIKE :tagPrefix)
        )
        ORDER BY isPinned DESC, Lomo.timestamp DESC, Lomo.id DESC
        """,
    )
    fun getMemosByTagPagingSource(
        tag: String,
        tagPrefix: String,
    ): PagingSource<Int, DefaultMainListMemoRow>

    @Query("SELECT DISTINCT tag FROM MemoTagCrossRef ORDER BY tag COLLATE NOCASE")
    fun getAllTagsFlow(): Flow<List<String>>

}
