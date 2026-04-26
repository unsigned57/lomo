package com.lomo.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.local.dao.DefaultMainListDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.DefaultMainListMemoRow
import com.lomo.data.local.entity.MemoEntity
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoQueryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoQueryRepositoryImpl
    @Inject
    constructor(
        private val memoDao: MemoDao,
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

        override fun getDefaultMainListPagingSource(): PagingSource<Int, Memo> =
            MemoRowMappingPagingSource(
                source = defaultMainListDao.getPagingSource(),
            )

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

private class MemoRowMappingPagingSource(
    private val source: PagingSource<Int, DefaultMainListMemoRow>,
) : PagingSource<Int, Memo>() {
    init {
        source.registerInvalidatedCallback(::invalidate)
    }

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
        state.anchorPosition?.let { anchor ->
            val pageSize = state.config.pageSize.takeIf { it > 0 } ?: return null
            anchor / pageSize
        }
}
