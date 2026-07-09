package com.lomo.app.testing.fakes

import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.MemoStatisticsRepository
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

class FakeMemoStatisticsRepository(
    private val store: FakeMemoStore,
) : MemoStatisticsRepository {
    override suspend fun getMemoStatistics(
        zone: ZoneId,
        today: LocalDate,
    ): MemoStatistics = store.computeMemoStatistics(zone = zone, today = today)

    override fun getMemoCountFlow(): Flow<Int> = store.observeMemoCount()

    override fun getMemoTimestampsFlow(): Flow<List<Long>> = store.observeMemoTimestamps()

    override fun getMemoCountByDateFlow(): Flow<Map<String, Int>> = store.observeMemoCountByDate()

    override fun getTagCountsFlow(): Flow<List<MemoTagCount>> = store.observeTagCounts()

    override fun getActiveDayCount(): Flow<Int> = store.observeActiveDayCount()
}
