package com.lomo.domain.usecase

import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.repository.MemoStatisticsRepository
import java.time.LocalDate
import java.time.ZoneId

data class MemoStatisticsDateSnapshot(
    val zone: ZoneId,
    val asOfDate: LocalDate,
)

class MemoStatisticsUseCase(
    private val memoStatisticsRepository: MemoStatisticsRepository,
    private val dateSnapshotProvider: () -> MemoStatisticsDateSnapshot = {
        val zone = ZoneId.systemDefault()
        MemoStatisticsDateSnapshot(
            zone = zone,
            asOfDate = LocalDate.now(zone),
        )
    },
) {
    suspend operator fun invoke(): MemoStatistics {
        val dateSnapshot = dateSnapshotProvider()
        return memoStatisticsRepository.getMemoStatistics(
            zone = dateSnapshot.zone,
            today = dateSnapshot.asOfDate,
        )
    }
}
