package com.lomo.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.local.dao.DefaultMainListDao
import com.lomo.data.local.dao.MemoBrowseDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.DefaultMainListMemoRow
import com.lomo.data.local.entity.MemoEntity
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.repository.MemoQueryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoQueryRepositoryImpl
    @Inject
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

        override fun getMainListPagingSource(
            query: String,
            filter: MemoListFilter,
        ): PagingSource<Int, Memo> {
            val queryInput = filter.toDefaultMainListQueryInput(query)
            return MemoRowMappingPagingSource(
                source =
                    defaultMainListDao.getPagingSource(
                        query = queryInput.matchQuery,
                        startDate = queryInput.startDate,
                        endDate = queryInput.endDate,
                        sortOption = queryInput.sortOption,
                        sortAscending = queryInput.sortAscending,
                    ),
            )
        }

        override fun getMainListCountFlow(
            query: String,
            filter: MemoListFilter,
        ): Flow<Int> {
            val queryInput = filter.toDefaultMainListQueryInput(query)
            return defaultMainListDao.getCountFlow(
                query = queryInput.matchQuery,
                startDate = queryInput.startDate,
                endDate = queryInput.endDate,
                sortOption = queryInput.sortOption,
                sortAscending = queryInput.sortAscending,
            )
        }

        override suspend fun getDefaultMainListIndex(id: String): Int? = defaultMainListDao.getIndex(id)

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

private fun DefaultMainListMemoRow.toDomain(): Memo = memo.toDomain(isPinned = isPinned)

private data class DefaultMainListQueryInput(
    val matchQuery: String,
    val startDate: String?,
    val endDate: String?,
    val sortOption: String,
    val sortAscending: Boolean,
)

private fun MemoListFilter.toDefaultMainListQueryInput(query: String): DefaultMainListQueryInput {
    val normalizedRange = listOfNotNull(startDate, endDate).sorted()
    val normalizedStart = normalizedRange.firstOrNull() ?: startDate
    val normalizedEnd = normalizedRange.lastOrNull() ?: endDate
    return DefaultMainListQueryInput(
        matchQuery = MemoFtsQueryBuilder.buildMatchQuery(query) ?: "",
        startDate = normalizedStart?.toString()?.replace("-", "_"),
        endDate = normalizedEnd?.toString()?.replace("-", "_"),
        sortOption = sortOption.name,
        sortAscending = sortAscending,
    )
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

private class MemoRowMappingPagingSource(
    private val source: PagingSource<Int, DefaultMainListMemoRow>,
) : PagingSource<Int, Memo>() {
    init {
        source.registerInvalidatedCallback(::invalidate)
    }

    override val jumpingSupported: Boolean
        get() = source.jumpingSupported

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> =
        when (val result = source.load(params)) {
            is LoadResult.Error -> LoadResult.Error(result.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
            is LoadResult.Page ->
                LoadResult.Page(
                    data = result.data.map(DefaultMainListMemoRow::toDomain),
                    prevKey = result.prevKey,
                    nextKey = result.nextKey,
                    itemsBefore = result.itemsBefore,
                    itemsAfter = result.itemsAfter,
                )
        }

    override fun getRefreshKey(state: PagingState<Int, Memo>): Int? =
        source.getRefreshKey(state.toDefaultMainListRowState())
}

private fun PagingState<Int, Memo>.toDefaultMainListRowState(): PagingState<Int, DefaultMainListMemoRow> =
    PagingState(
        pages =
            pages.map { page ->
                PagingSource.LoadResult.Page(
                    data =
                        page.data.map { memo ->
                            DefaultMainListMemoRow(
                                memo = MemoEntity.fromDomain(memo),
                                isPinned = memo.isPinned,
                            )
                        },
                    prevKey = page.prevKey,
                    nextKey = page.nextKey,
                    itemsBefore = page.itemsBefore,
                    itemsAfter = page.itemsAfter,
                )
            },
        anchorPosition = anchorPosition,
        config = config,
        // PagingState does not expose leadingPlaceholderCount in this artifact; the mapped pages
        // still preserve anchorPosition plus itemsBefore/itemsAfter for delegated refresh-key use.
        leadingPlaceholderCount = 0,
    )
