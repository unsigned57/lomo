package com.lomo.data.local.dao

import androidx.paging.PagingSource
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Embedded
import androidx.room3.Query
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import com.lomo.data.local.entity.MemoEntity

@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
interface DefaultMainListDao {
    @Query(
        """
        SELECT Lomo.*, CASE WHEN MemoPin.memoId IS NULL THEN 0 ELSE 1 END AS isPinned
        FROM Lomo
        LEFT JOIN MemoPin ON Lomo.id = MemoPin.memoId
        ORDER BY isPinned DESC, Lomo.timestamp DESC, Lomo.id DESC
        """,
    )
    fun getPagingSource(): PagingSource<Int, DefaultMainListMemoRow>

    @Query(
        """
        SELECT Lomo.*, CASE WHEN MemoPin.memoId IS NULL THEN 0 ELSE 1 END AS isPinned
        FROM Lomo
        LEFT JOIN MemoPin ON Lomo.id = MemoPin.memoId
        ORDER BY isPinned DESC, Lomo.timestamp DESC, Lomo.id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getPage(
        limit: Int,
        offset: Int,
    ): List<DefaultMainListMemoRow>

    @Query(
        """
        SELECT (
            SELECT COUNT(*)
            FROM Lomo AS candidate
            LEFT JOIN MemoPin AS candidatePin ON candidate.id = candidatePin.memoId
            WHERE
                (CASE WHEN candidatePin.memoId IS NULL THEN 0 ELSE 1 END) >
                    (CASE WHEN targetPin.memoId IS NULL THEN 0 ELSE 1 END)
                OR (
                    (CASE WHEN candidatePin.memoId IS NULL THEN 0 ELSE 1 END) =
                        (CASE WHEN targetPin.memoId IS NULL THEN 0 ELSE 1 END)
                    AND (
                        candidate.timestamp > target.timestamp
                        OR (
                            candidate.timestamp = target.timestamp
                            AND candidate.id > target.id
                        )
                    )
                )
        )
        FROM Lomo AS target
        LEFT JOIN MemoPin AS targetPin ON target.id = targetPin.memoId
        WHERE target.id = :id
        """,
    )
    suspend fun getIndex(id: String): Int?
}

data class DefaultMainListMemoRow(
    @Embedded val memo: MemoEntity,
    @ColumnInfo(name = "isPinned") val isPinned: Boolean,
)
