package com.lomo.domain.testing.fakes

import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.model.MemoSidebarStatistics
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.MemoStatisticsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

    override fun getSidebarStatisticsFlow(): Flow<MemoSidebarStatistics> =
        combine(store.observeMemoCount(), store.observeMemoCountByDate(), store.observeTagCounts()) {
                count,
                dates,
                tags,
            ->
            MemoSidebarStatistics(
                count,
                dates.mapNotNull { (date, value) ->
                    StorageFilenameFormats.parseOrNull(date)?.let { it to value }
                }.toMap(),
                tags,
            )
        }

    override fun getMemoCountByDateFlow(): Flow<Map<String, Int>> = store.observeMemoCountByDate()

    override fun getTagCountsFlow(): Flow<List<MemoTagCount>> = store.observeTagCounts()

    override fun getActiveDayCount(): Flow<Int> = store.observeActiveDayCount()
}
