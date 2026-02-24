package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject

/**
 * Picks a deterministic "daily review" window from the full memo stream.
 *
 * For a given [seedDate], the same page offset is produced so results are stable within that day.
 */
class DailyReviewQueryUseCase
    @Inject
    constructor(
        private val repository: MemoRepository,
    ) {
        suspend operator fun invoke(
            limit: Int,
            seedDate: LocalDate,
        ): List<Memo> {
            if (limit <= 0) return emptyList()

            val allMemos = repository.getAllMemosList().first()
            if (allMemos.isEmpty()) return emptyList()

            val safeLimit = limit.coerceAtMost(allMemos.size)
            val maxOffset = (allMemos.size - safeLimit).coerceAtLeast(0)
            val offset =
                if (maxOffset == 0) {
                    0
                } else {
                    kotlin.random.Random(seedDate.toEpochDay()).nextInt(maxOffset + 1)
                }
            return allMemos.drop(offset).take(safeLimit)
        }
    }
