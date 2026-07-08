package com.lomo.data.repository
import androidx.paging.PagingSource
import com.lomo.data.local.dao.DefaultMainListDao
import com.lomo.data.local.dao.MemoBrowseDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.DefaultMainListMemoRow
import com.lomo.data.local.entity.MemoEntity
import com.lomo.domain.model.DailyReviewCandidateBoundary
import com.lomo.domain.model.DailyReviewCandidateCursor
import com.lomo.domain.model.DailyReviewCandidatePage
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoFilterCriterion
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.repository.MemoQueryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
class MemoQueryRepositoryImpl
constructor(
        private val memoDao: MemoDao,
        private val memoBrowseDao: MemoBrowseDao,
        private val defaultMainListDao: DefaultMainListDao,
        private val memoPinDao: MemoPinDao,
        private val synchronizer: MemoSynchronizer,
    ) : MemoQueryRepository {
        override fun getAllMemosList(): Flow<List<Memo>> =
            combine(
                memoDao.getAllMemosFlow(),
                memoPinDao.getPinnedMemoIdsFlow().map { pinnedIds -> pinnedIds.toSet() },
            ) { entities, pinnedMemoIds ->
                entities.mapToPinnedDomain(pinnedMemoIds)
            }.flowOn(Dispatchers.Default)
        override fun getMemosByDateRange(
            startDate: LocalDate?,
            endDate: LocalDate?,
        ): Flow<List<Memo>> {
            val normalizedRange = listOfNotNull(startDate, endDate).sorted()
            val normalizedStart = normalizedRange.firstOrNull()
            val normalizedEnd = normalizedRange.lastOrNull()
            return combine(
                memoBrowseDao.getMemosByTimestampRangeFlow(
                    startTimestampInclusive = normalizedStart.toStartOfDayEpochMillis(),
                    endTimestampExclusive = normalizedEnd.toExclusiveEndOfDayEpochMillis(),
                ),
                memoPinDao.getPinnedMemoIdsFlow().map { pinnedIds -> pinnedIds.toSet() },
            ) { entities, pinnedMemoIds ->
                entities.mapToPinnedDomain(pinnedMemoIds)
            }.flowOn(Dispatchers.Default)
        }
        override fun getGalleryMemosList(): Flow<List<Memo>> =
            combine(
                memoBrowseDao.getGalleryMemosFlow(),
                memoPinDao.getPinnedMemoIdsFlow().map { pinnedIds -> pinnedIds.toSet() },
            ) { entities, pinnedMemoIds ->
                entities.mapToPinnedDomain(pinnedMemoIds)
            }.flowOn(Dispatchers.Default)
        override suspend fun getRecentMemos(limit: Int): List<Memo> {
            val pinnedMemoIds = memoPinDao.getPinnedMemoIds().toSet()
            return memoDao.getRecentMemos(limit).mapToPinnedDomain(pinnedMemoIds)
        }
        override suspend fun getMemosPage(
            limit: Int,
            offset: Int,
        ): List<Memo> =
            if (limit <= 0 || offset < 0) {
                emptyList()
            } else {
                defaultMainListDao.getPage(limit = limit, offset = offset).map(DefaultMainListMemoRow::toDomain)
            }
        override suspend fun getMemoCount(): Int = memoDao.getMemoCountSync()
        override suspend fun getDailyReviewCandidateBoundary(): DailyReviewCandidateBoundary? {
            val maxRowId = defaultMainListDao.getDailyReviewCandidateMaxRowId() ?: return null
            val observedCount = defaultMainListDao.getDailyReviewCandidateCount(maxRowId = maxRowId)
            if (observedCount <= 0) {
                return null
            }
            val row =
                defaultMainListDao
                    .getDailyReviewCandidatePage(
                        maxRowId = maxRowId,
                        cursorIsPinned = null,
                        cursorTimestamp = null,
                        cursorId = null,
                        limit = 1,
                    ).firstOrNull()
                    ?: return null
            return DailyReviewCandidateBoundary(
                isPinned = row.isPinned,
                timestamp = row.memo.timestamp,
                id = row.memo.id,
                token = maxRowId.toDailyReviewBoundaryToken(),
                observedCount = observedCount,
            )
        }
        override suspend fun getDailyReviewCandidatePage(
            boundary: DailyReviewCandidateBoundary,
            cursor: DailyReviewCandidateCursor?,
            limit: Int,
        ): DailyReviewCandidatePage {
            if (limit <= 0) {
                return DailyReviewCandidatePage(ids = emptyList(), nextCursor = cursor)
            }
            val maxRowId =
                boundary.token.toDailyReviewBoundaryMaxRowId()
                    ?: return DailyReviewCandidatePage(ids = emptyList(), nextCursor = cursor)
            val rows =
                defaultMainListDao.getDailyReviewCandidatePage(
                    maxRowId = maxRowId,
                    cursorIsPinned = cursor?.isPinned,
                    cursorTimestamp = cursor?.timestamp,
                    cursorId = cursor?.id,
                    limit = limit,
                )
            val lastRow = rows.lastOrNull()
            return DailyReviewCandidatePage(
                ids = rows.map { row -> row.memo.id },
                nextCursor =
                    lastRow?.let { row ->
                        DailyReviewCandidateCursor(
                            isPinned = row.isPinned,
                            timestamp = row.memo.timestamp,
                            id = row.memo.id,
                            token = boundary.token,
                            position = (cursor?.position ?: 0) + rows.size,
                        )
                    } ?: cursor,
            )
        }
        override fun getMainListPagingSource(spec: MemoQuerySpec): PagingSource<Int, Memo> {
            val queryInput = spec.toMainListDaoQueryInput()
            return when (val searchPlan = queryInput.searchPlan) {
                DefaultMainListSearchPlan.NoSearchableTokens -> NoSearchableTokensPagingSource()
                is DefaultMainListSearchPlan.Match ->
                    MemoRowMappingPagingSource(
                        source =
                            defaultMainListDao.getPagingSource(
                                query = searchPlan.matchQuery,
                                startDate = queryInput.startDate,
                                endDate = queryInput.endDate,
                                sortOption = queryInput.sortOption,
                                sortAscending = queryInput.sortAscending,
                                hasTodo = queryInput.hasTodo,
                                hasAttachment = queryInput.hasAttachment,
                                hasUrl = queryInput.hasUrl,
                            ),
                    )
                DefaultMainListSearchPlan.NoQuery ->
                    MemoRowMappingPagingSource(
                        source =
                            defaultMainListDao.getPagingSource(
                                query = "",
                                startDate = queryInput.startDate,
                                endDate = queryInput.endDate,
                                sortOption = queryInput.sortOption,
                                sortAscending = queryInput.sortAscending,
                                hasTodo = queryInput.hasTodo,
                                hasAttachment = queryInput.hasAttachment,
                                hasUrl = queryInput.hasUrl,
                            ),
                    )
            }
        }
        override fun getMainListCountFlow(spec: MemoQuerySpec): Flow<Int> {
            val queryInput = spec.toMainListDaoQueryInput()
            return when (val searchPlan = queryInput.searchPlan) {
                DefaultMainListSearchPlan.NoSearchableTokens -> flowOf(0)
                is DefaultMainListSearchPlan.Match ->
                    defaultMainListDao.getCountFlow(
                        query = searchPlan.matchQuery,
                        startDate = queryInput.startDate,
                        endDate = queryInput.endDate,
                        sortOption = queryInput.sortOption,
                        sortAscending = queryInput.sortAscending,
                        hasTodo = queryInput.hasTodo,
                        hasAttachment = queryInput.hasAttachment,
                        hasUrl = queryInput.hasUrl,
                    )
                DefaultMainListSearchPlan.NoQuery ->
                    defaultMainListDao.getCountFlow(
                        query = "",
                        startDate = queryInput.startDate,
                        endDate = queryInput.endDate,
                        sortOption = queryInput.sortOption,
                        sortAscending = queryInput.sortAscending,
                        hasTodo = queryInput.hasTodo,
                        hasAttachment = queryInput.hasAttachment,
                        hasUrl = queryInput.hasUrl,
                    )
            }
        }
        override suspend fun getDefaultMainListIndexInWindow(
            id: String,
            limit: Int,
        ): Int? {
            if (limit <= 0) {
                return null
            }
            val index = defaultMainListDao.getDefaultMainListHeadIds(limit = limit).indexOf(id)
            return index.takeIf { value -> value >= 0 }
        }
        override suspend fun getMemoById(id: String): Memo? {
            val entity = memoDao.getMemo(id) ?: return null
            val pinnedMemoIds = memoPinDao.getPinnedMemoIds().toSet()
            return entity.toDomain(isPinned = entity.id in pinnedMemoIds)
        }
        override fun isSyncing(): Flow<Boolean> = synchronizer.isSyncing
    }
internal fun List<MemoEntity>.mapToPinnedDomain(pinnedMemoIds: Set<String>): List<Memo> =
    map { entity ->
        entity.toDomain(isPinned = entity.id in pinnedMemoIds)
    }
private fun Long.toDailyReviewBoundaryToken(): String = "$DAILY_REVIEW_BOUNDARY_TOKEN_PREFIX$this"
private fun String.toDailyReviewBoundaryMaxRowId(): Long? =
    takeIf { token -> token.startsWith(DAILY_REVIEW_BOUNDARY_TOKEN_PREFIX) }
        ?.removePrefix(DAILY_REVIEW_BOUNDARY_TOKEN_PREFIX)
        ?.toLongOrNull()
private const val DAILY_REVIEW_BOUNDARY_TOKEN_PREFIX = "daily-review-boundary-"
private data class DefaultMainListQueryInput(
    val searchPlan: DefaultMainListSearchPlan,
    val startDate: String?,
    val endDate: String?,
    val sortOption: String,
    val sortAscending: Boolean,
    val hasTodo: Boolean?,
    val hasAttachment: Boolean?,
    val hasUrl: Boolean?,
)
private sealed interface DefaultMainListSearchPlan {
    data object NoQuery : DefaultMainListSearchPlan
    data class Match(
        val matchQuery: String,
    ) : DefaultMainListSearchPlan
    data object NoSearchableTokens : DefaultMainListSearchPlan
}
private fun MemoQuerySpec.toMainListDaoQueryInput(): DefaultMainListQueryInput {
    return DefaultMainListQueryInput(
        searchPlan = normalizedQueryText.toDefaultMainListSearchPlan(),
        startDate = dateRange.startDate?.toDaoDateKey(),
        endDate = dateRange.endDate?.toDaoDateKey(),
        sortOption = sort.option.name,
        sortAscending = sort.ascending,
        hasTodo = criteria.booleanFor(MemoFilterCriterion.HasTodo, MemoFilterCriterion.NoTodo),
        hasAttachment =
            criteria.booleanFor(
                MemoFilterCriterion.HasAttachment,
                MemoFilterCriterion.NoAttachment,
            ),
        hasUrl = criteria.booleanFor(MemoFilterCriterion.HasUrl, MemoFilterCriterion.NoUrl),
    )
}
private fun Set<MemoFilterCriterion>.booleanFor(
    positive: MemoFilterCriterion,
    negative: MemoFilterCriterion,
): Boolean? =
    when {
        positive in this -> true
        negative in this -> false
        else -> null
    }
private fun LocalDate.toDaoDateKey(): String = toString().replace("-", "_")
private fun String.toDefaultMainListSearchPlan(): DefaultMainListSearchPlan {
    val normalizedQuery = trim()
    if (normalizedQuery.isEmpty()) {
        return DefaultMainListSearchPlan.NoQuery
    }
    return MemoFtsQueryBuilder
        .buildMatchQuery(normalizedQuery)
        ?.let(DefaultMainListSearchPlan::Match)
        ?: DefaultMainListSearchPlan.NoSearchableTokens
}
private fun LocalDate?.toStartOfDayEpochMillis(): Long =
    this
        ?.atStartOfDay(ZoneId.systemDefault())
        ?.toInstant()
        ?.toEpochMilli()
        ?: Long.MIN_VALUE
private fun LocalDate?.toExclusiveEndOfDayEpochMillis(): Long =
    this
        ?.takeUnless { it == LocalDate.MAX }
        ?.plusDays(1)
        ?.atStartOfDay(ZoneId.systemDefault())
        ?.toInstant()
        ?.toEpochMilli()
        ?: Long.MAX_VALUE
private class NoSearchableTokensPagingSource : PagingSource<Int, Memo>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> =
        LoadResult.Page(
            data = emptyList(),
            prevKey = null,
            nextKey = null,
        )
    override fun getRefreshKey(state: androidx.paging.PagingState<Int, Memo>): Int? = null
}
