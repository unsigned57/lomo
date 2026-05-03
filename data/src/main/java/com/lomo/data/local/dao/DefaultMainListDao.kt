package com.lomo.data.local.dao

import androidx.paging.PagingSource
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Embedded
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoPinEntity

@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
interface DefaultMainListDao {
    @RawQuery(
        observedEntities = [MemoEntity::class, MemoPinEntity::class],
    )
    fun getPagingSourceRaw(query: RoomRawQuery): PagingSource<Int, DefaultMainListMemoRow>

    fun getPagingSource(
        query: String,
        startDate: String?,
        endDate: String?,
        sortOption: String,
        sortAscending: Boolean,
    ): PagingSource<Int, DefaultMainListMemoRow> =
        getPagingSourceRaw(
            buildMainListPagingQuery(
                query = query,
                startDate = startDate,
                endDate = endDate,
                sortOption = sortOption,
                sortAscending = sortAscending,
            ),
        )

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

private fun buildMainListPagingQuery(
    query: String,
    startDate: String?,
    endDate: String?,
    sortOption: String,
    sortAscending: Boolean,
): RoomRawQuery {
    val args = mutableListOf<String>()
    val whereClauses = buildList {
        if (query.isNotBlank()) {
            add(
                """
                Lomo.rowid IN (
                    SELECT Lomo.rowid
                    FROM Lomo
                    INNER JOIN lomo_fts ON lomo_fts.rowid = Lomo.rowid
                    WHERE lomo_fts MATCH ?
                )
                """.trimIndent(),
            )
            args += query
        }
        if (startDate != null) {
            add("Lomo.date >= ?")
            args += startDate
        }
        if (endDate != null) {
            add("Lomo.date <= ?")
            args += endDate
        }
    }
    val orderColumn =
        when (sortOption) {
            "UPDATED_TIME" -> "Lomo.updatedAt"
            else -> "Lomo.timestamp"
        }
    val orderDirection = if (sortAscending) "ASC" else "DESC"
    val sql =
        buildString {
            append(
                """
                SELECT Lomo.*, CASE WHEN MemoPin.memoId IS NULL THEN 0 ELSE 1 END AS isPinned
                FROM Lomo
                LEFT JOIN MemoPin ON Lomo.id = MemoPin.memoId
                """.trimIndent(),
            )
            if (whereClauses.isNotEmpty()) {
                append(" WHERE ")
                append(whereClauses.joinToString(separator = " AND "))
            }
            append(" ORDER BY isPinned DESC, ")
            append(orderColumn)
            append(' ')
            append(orderDirection)
            append(", Lomo.timestamp ")
            append(orderDirection)
            append(", Lomo.id ")
            append(orderDirection)
        }
    return RoomRawQuery(
        sql = sql,
        onBindStatement = { statement ->
            args.forEachIndexed { index, value ->
                statement.bindText(index = index + 1, value = value)
            }
        },
    )
}
