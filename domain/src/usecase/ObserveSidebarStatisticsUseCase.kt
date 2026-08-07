package com.lomo.domain.usecase

import com.lomo.domain.model.MemoSidebarStatistics
import com.lomo.domain.repository.MemoStatisticsRepository
import kotlinx.coroutines.flow.Flow

class ObserveSidebarStatisticsUseCase(
    private val memoStatisticsRepository: MemoStatisticsRepository,
) {
    operator fun invoke(): Flow<MemoSidebarStatistics> =
        memoStatisticsRepository.getSidebarStatisticsFlow()
}
