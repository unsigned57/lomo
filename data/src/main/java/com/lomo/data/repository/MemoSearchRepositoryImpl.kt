package com.lomo.data.repository

import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.data.util.SearchTokenizer
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.MemoSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoSearchRepositoryImpl
    @Inject
    constructor(
        private val memoSearchDao: MemoSearchDao,
        private val memoPinDao: MemoPinDao,
    ) : MemoSearchRepository {
        override fun searchMemosList(query: String) =
            combine(
                memoSearchSource(query),
                memoPinDao.getPinnedMemoIdsFlow().map { pinnedIds -> pinnedIds.toSet() },
            ) { entities, pinnedMemoIds ->
                entities.mapToPinnedDomain(pinnedMemoIds)
            }.flowOn(Dispatchers.Default)

        override fun getMemosByTagList(tag: String) =
            combine(
                memoSearchDao.getMemosByTagFlow(tag, "$tag/%"),
                memoPinDao.getPinnedMemoIdsFlow().map { pinnedIds -> pinnedIds.toSet() },
            ) { entities, pinnedMemoIds ->
                entities.mapToPinnedDomain(pinnedMemoIds)
            }.flowOn(Dispatchers.Default)

        override fun getMemoCountFlow() = memoSearchDao.getMemoCount()

        override fun getMemoTimestampsFlow() = memoSearchDao.getAllTimestamps()

        override fun getMemoCountByDateFlow() =
            memoSearchDao
                .getMemoCountByDateFlow()
                .map { rows ->
                    buildMap(rows.size) {
                        rows.forEach { row ->
                            put(row.date, row.count)
                        }
                    }
                }.flowOn(Dispatchers.Default)

        override fun getTagCountsFlow() =
            memoSearchDao
                .getTagCountsFlow()
                .map { rows ->
                    rows.map { row ->
                        MemoTagCount(name = row.name, count = row.count)
                    }
                }.flowOn(Dispatchers.Default)

        override fun getActiveDayCount() = memoSearchDao.getActiveDayCount()

        private fun memoSearchSource(query: String) =
            SearchTokenizer
                .tokenizeQueryTerms(query.trim())
                .take(MAX_SEARCH_TOKENS)
                .takeIf(List<String>::isNotEmpty)
                ?.joinToString(" ") { token -> "$token*" }
                ?.let(memoSearchDao::searchMemosByFtsFlow)
                ?: memoSearchDao.searchMemosFlow(query.trim())

        private companion object {
            private const val MAX_SEARCH_TOKENS = 5
        }
    }
