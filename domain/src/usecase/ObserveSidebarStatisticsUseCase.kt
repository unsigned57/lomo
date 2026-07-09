package com.lomo.domain.usecase

import com.lomo.domain.model.MemoSidebarStatistics
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.repository.MemoStatisticsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class ObserveSidebarStatisticsUseCase(
    private val memoStatisticsRepository: MemoStatisticsRepository,
) {
    operator fun invoke(): Flow<MemoSidebarStatistics> =
        combine(
            memoStatisticsRepository.getMemoCountFlow(),
            memoStatisticsRepository.getMemoCountByDateFlow(),
            memoStatisticsRepository.getTagCountsFlow(),
        ) { memoCount, memoCountByDateRaw, tagCounts ->
            MemoSidebarStatistics(
                memoCount = memoCount,
                memoCountByDate =
                    memoCountByDateRaw
                        .asSequence()
                        .mapNotNull { (dateStr, count) ->
                            StorageFilenameFormats.parseOrNull(dateStr)?.let { parsed -> parsed to count }
                        }.toMap(),
                tagCounts = tagCounts,
            )
        }
}
