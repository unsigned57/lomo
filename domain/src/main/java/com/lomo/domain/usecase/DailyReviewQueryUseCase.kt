package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import java.time.LocalDate
import kotlin.random.Random
import javax.inject.Inject

/**
 * Picks a deterministic "daily review" random sample from the memo stream.
 *
 * For a given [seedDate], the same random order is produced so results are stable within that day,
 * while still sampling from the entire memo set instead of a contiguous window.
 *
 * Uses repository paging/count contracts to avoid materializing the full list in memory when
 * storage can provide indexed access efficiently.
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

            val totalMemoCount = repository.getMemoCount()
            if (totalMemoCount <= 0) return emptyList()

            val safeLimit = limit.coerceAtMost(totalMemoCount)
            if (safeLimit <= 0) return emptyList()

            val sampledIndices =
                if (safeLimit == totalMemoCount) {
                    IntArray(totalMemoCount) { it }
                } else {
                    val dailyRandom = Random(seedDate.toEpochDay())
                    sampleIndicesWithoutReplacement(totalMemoCount, safeLimit, dailyRandom)
                }

            if (sampledIndices.isEmpty()) return emptyList()
            return fetchMemosByIndices(sampledIndices)
        }

        private suspend fun fetchMemosByIndices(indices: IntArray): List<Memo> {
            val selectionsByPage =
                indices.withIndex().groupBy(
                    keySelector = { indexedValue -> indexedValue.value / DAILY_REVIEW_PAGE_SIZE },
                )
            val sampledMemos = arrayOfNulls<Memo>(indices.size)

            for (pageIndex in selectionsByPage.keys.sorted()) {
                val pageOffset = pageIndex * DAILY_REVIEW_PAGE_SIZE
                val pageMemos = repository.getMemosPage(limit = DAILY_REVIEW_PAGE_SIZE, offset = pageOffset)
                val pageSelections = selectionsByPage[pageIndex].orEmpty()

                for (selection in pageSelections) {
                    val indexWithinPage = selection.value - pageOffset
                    if (indexWithinPage in pageMemos.indices) {
                        sampledMemos[selection.index] = pageMemos[indexWithinPage]
                    }
                }
            }

            return sampledMemos.filterNotNull()
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

        private companion object {
            private const val DAILY_REVIEW_PAGE_SIZE = 64
        }
    }
