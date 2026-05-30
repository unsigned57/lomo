package com.lomo.domain.usecase

import com.lomo.domain.repository.MemoStatisticsRepository
import kotlinx.coroutines.flow.Flow

class ObserveActiveDayCountUseCase(
    private val memoStatisticsRepository: MemoStatisticsRepository,
) {
    operator fun invoke(): Flow<Int> = memoStatisticsRepository.getActiveDayCount()
}
