package com.lomo.data.repository

import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.data.local.MemoFtsTelemetry
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.MemoSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import timber.log.Timber
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

        /**
         * Escapes LIKE wildcard characters (`%` and `_`) using backslash so that user-supplied
         * query strings are treated as literal text by the SQL `LIKE … ESCAPE '\'` clause.
         */
        internal fun escapeLikeQuery(raw: String): String =
            raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        private fun memoSearchSource(query: String) =
            MemoFtsQueryBuilder
                .buildMatchQuery(query)
                ?.let { matchQuery ->
                    flow {
                        val startedAt = System.currentTimeMillis()
                        try {
                            memoSearchDao.searchMemosByFtsFlow(matchQuery).collect { rows ->
                                MemoFtsTelemetry.recordSearchResult(
                                    durationMs = System.currentTimeMillis() - startedAt,
                                    isEmptyResult = rows.isEmpty(),
                                )
                                emit(rows)
                            }
                        } catch (e: IllegalArgumentException) {
                            Timber.w(e, "FTS MATCH syntax error; falling back to empty results. query=%s", matchQuery)
                            MemoFtsTelemetry.recordMatchSyntaxError(e)
                            emit(emptyList())
                        }
                    }
                } ?: flowOf(emptyList())

    }
