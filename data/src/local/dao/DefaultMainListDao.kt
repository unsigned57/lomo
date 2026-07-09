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
import kotlinx.coroutines.flow.Flow

@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
interface DefaultMainListDao {
    @RawQuery(
        observedEntities = [MemoEntity::class, MemoPinEntity::class],
    )
    fun getPagingSourceRaw(query: RoomRawQuery): PagingSource<Int, DefaultMainListMemoRow>

    @RawQuery(
        observedEntities = [MemoEntity::class],
    )
    fun getCountFlowRaw(query: RoomRawQuery): Flow<Int>

    fun getPagingSource(
        query: String,
        startDate: String?,
        endDate: String?,
        sortOption: String,
        sortAscending: Boolean,
        hasTodo: Boolean?,
        hasAttachment: Boolean?,
        hasUrl: Boolean?,
    ): PagingSource<Int, DefaultMainListMemoRow> =
        getPagingSourceRaw(
            buildMainListPagingQuery(
                query = query,
                startDate = startDate,
                endDate = endDate,
                sortOption = sortOption,
                sortAscending = sortAscending,
                hasTodo = hasTodo,
                hasAttachment = hasAttachment,
                hasUrl = hasUrl,
            ),
        )

    fun getCountFlow(
        query: String,
        startDate: String?,
        endDate: String?,
        sortOption: String,
        sortAscending: Boolean,
        hasTodo: Boolean?,
        hasAttachment: Boolean?,
        hasUrl: Boolean?,
    ): Flow<Int> =
        getCountFlowRaw(
            buildMainListCountQuery(
                query = query,
                startDate = startDate,
                endDate = endDate,
                hasTodo = hasTodo,
                hasAttachment = hasAttachment,
                hasUrl = hasUrl,
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

    @Query("SELECT MAX(rowid) FROM Lomo")
    suspend fun getDailyReviewCandidateMaxRowId(): Long?

    @Query("SELECT COUNT(*) FROM Lomo WHERE rowid <= :maxRowId")
    suspend fun getDailyReviewCandidateCount(maxRowId: Long): Int

    @Query(
        """
        SELECT Lomo.*, CASE WHEN MemoPin.memoId IS NULL THEN 0 ELSE 1 END AS isPinned
        FROM Lomo
        LEFT JOIN MemoPin ON Lomo.id = MemoPin.memoId
        WHERE Lomo.rowid <= :maxRowId
            AND (
                :cursorId IS NULL
                OR (CASE WHEN MemoPin.memoId IS NULL THEN 0 ELSE 1 END) <
                    CASE WHEN :cursorIsPinned THEN 1 ELSE 0 END
                OR (
                    (CASE WHEN MemoPin.memoId IS NULL THEN 0 ELSE 1 END) =
                        CASE WHEN :cursorIsPinned THEN 1 ELSE 0 END
                    AND (
                        Lomo.timestamp < :cursorTimestamp
                        OR (
                            Lomo.timestamp = :cursorTimestamp
                            AND Lomo.id < :cursorId
                        )
                    )
                )
            )
        ORDER BY isPinned DESC, Lomo.timestamp DESC, Lomo.id DESC
        LIMIT :limit
        """,
    )
    suspend fun getDailyReviewCandidatePage(
        maxRowId: Long,
        cursorIsPinned: Boolean?,
        cursorTimestamp: Long?,
        cursorId: String?,
        limit: Int,
    ): List<DefaultMainListMemoRow>

    @Query(
        """
        SELECT Lomo.id
        FROM Lomo
        LEFT JOIN MemoPin ON Lomo.id = MemoPin.memoId
        ORDER BY CASE WHEN MemoPin.memoId IS NULL THEN 0 ELSE 1 END DESC, Lomo.timestamp DESC, Lomo.id DESC
        LIMIT :limit
        """,
    )
    suspend fun getDefaultMainListHeadIds(limit: Int): List<String>
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
    hasTodo: Boolean?,
    hasAttachment: Boolean?,
    hasUrl: Boolean?,
): RoomRawQuery {
    val filter =
        buildMainListQueryFilter(
            query = query,
            startDate = startDate,
            endDate = endDate,
            hasTodo = hasTodo,
            hasAttachment = hasAttachment,
            hasUrl = hasUrl,
        )
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
            if (filter.whereClauses.isNotEmpty()) {
                append(" WHERE ")
                append(filter.whereClauses.joinToString(separator = " AND "))
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
            filter.args.forEachIndexed { index, value ->
                statement.bindText(index = index + 1, value = value)
            }
        },
    )
}

private fun buildMainListCountQuery(
    query: String,
    startDate: String?,
    endDate: String?,
    hasTodo: Boolean?,
    hasAttachment: Boolean?,
    hasUrl: Boolean?,
): RoomRawQuery {
    val filter =
        buildMainListQueryFilter(
            query = query,
            startDate = startDate,
            endDate = endDate,
            hasTodo = hasTodo,
            hasAttachment = hasAttachment,
            hasUrl = hasUrl,
        )
    val sql =
        buildString {
            append("SELECT COUNT(*) FROM Lomo")
            if (filter.whereClauses.isNotEmpty()) {
                append(" WHERE ")
                append(filter.whereClauses.joinToString(separator = " AND "))
            }
        }
    return RoomRawQuery(
        sql = sql,
        onBindStatement = { statement ->
            filter.args.forEachIndexed { index, value ->
                statement.bindText(index = index + 1, value = value)
            }
        },
    )
}

private data class MainListQueryFilter(
    val whereClauses: List<String>,
    val args: List<String>,
)

private fun buildMainListQueryFilter(
    query: String,
    startDate: String?,
    endDate: String?,
    hasTodo: Boolean?,
    hasAttachment: Boolean?,
    hasUrl: Boolean?,
): MainListQueryFilter {
    val args = mutableListOf<String>()
    val whereClauses =
        buildList {
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
            addContentFlagClause(hasTodo, "Lomo.hasTodo")
            addContentFlagClause(hasAttachment, "Lomo.hasAttachment")
            addContentFlagClause(hasUrl, "Lomo.hasUrl")
        }
    return MainListQueryFilter(whereClauses = whereClauses, args = args)
}

private fun MutableList<String>.addContentFlagClause(
    value: Boolean?,
    columnName: String,
) {
    when (value) {
        true -> add("$columnName = 1")
        false -> add("$columnName = 0")
        null -> Unit
    }
}
