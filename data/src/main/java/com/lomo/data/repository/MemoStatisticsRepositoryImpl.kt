package com.lomo.data.repository

import com.lomo.data.local.dao.MemoStatisticsDao
import com.lomo.data.local.dao.MemoStatisticsProjectionRow
import com.lomo.data.local.dao.TagCountRow
import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.model.MemoStatisticsCalculator
import com.lomo.domain.model.MemoStatisticsMemoProjection
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.MemoStatisticsRepository
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoStatisticsRepositoryImpl
    @Inject
    constructor(
        private val memoStatisticsDao: MemoStatisticsDao,
        private val dispatcherProvider: DispatcherProvider,
    ) : MemoStatisticsRepository {
        override suspend fun getMemoStatistics(
            zone: ZoneId,
            today: LocalDate,
        ): MemoStatistics =
            withContext(dispatcherProvider.io) {
                MemoStatisticsCalculator.compute(
                    memos =
                        memoStatisticsDao
                            .getMemoStatisticsProjection()
                            .map(MemoStatisticsProjectionRow::toDomainProjection),
                    tagCounts = memoStatisticsDao.getTagCounts().map(TagCountRow::toDomain),
                    zone = zone,
                    today = today,
                )
            }

        override fun getMemoCountFlow() = memoStatisticsDao.getMemoCount()

        override fun getMemoTimestampsFlow() = memoStatisticsDao.getAllTimestamps()

        override fun getMemoCountByDateFlow() =
            memoStatisticsDao
                .getMemoCountByDateFlow()
                .map { rows ->
                    buildMap(rows.size) {
                        rows.forEach { row ->
                            put(row.date, row.count)
                        }
                    }
                }.flowOn(dispatcherProvider.io)

        override fun getTagCountsFlow() =
            memoStatisticsDao
                .getTagCountsFlow()
                .map { rows -> rows.map(TagCountRow::toDomain) }
                .flowOn(dispatcherProvider.io)

        override fun getActiveDayCount() = memoStatisticsDao.getActiveDayCount()
    }

private fun TagCountRow.toDomain(): MemoTagCount = MemoTagCount(name = name, count = count)

private fun MemoStatisticsProjectionRow.toDomainProjection(): MemoStatisticsMemoProjection =
    MemoStatisticsMemoProjection(
        timestamp = timestamp,
        wordCount = statisticsWordCount,
        characterCount = statisticsCharacterCount,
    )
