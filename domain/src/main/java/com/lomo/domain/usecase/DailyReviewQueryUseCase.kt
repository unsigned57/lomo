package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import kotlin.random.Random
import javax.inject.Inject

/**
 * Picks a deterministic "daily review" random sample from the full memo stream.
 *
 * For a given [seedDate], the same random order is produced so results are stable within that day,
 * while still sampling from the entire memo set instead of a contiguous window.
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
            if (safeLimit == allMemos.size) return allMemos

            val dailyRandom = Random(seedDate.toEpochDay())
            val sampledIndices = sampleIndicesWithoutReplacement(allMemos.size, safeLimit, dailyRandom)
            return buildList(capacity = safeLimit) {
                for (index in sampledIndices) {
                    add(allMemos[index])
                }
            }
        }

        /**
         * Deterministic partial Fisher-Yates sampling.
         *
         * Selects [sampleSize] unique indices in O(sampleSize) expected space/time without
         * allocating/reordering the entire source list.
         */
        private fun sampleIndicesWithoutReplacement(
            populationSize: Int,
            sampleSize: Int,
            random: Random,
        ): IntArray {
            val swaps = HashMap<Int, Int>(sampleSize * 2)

            return IntArray(sampleSize) { i ->
                val j = random.nextInt(i, populationSize)
                val valueAtI = swaps[i] ?: i
                val valueAtJ = swaps[j] ?: j

                swaps[i] = valueAtJ
                swaps[j] = valueAtI
                valueAtJ
            }
        }
    }
