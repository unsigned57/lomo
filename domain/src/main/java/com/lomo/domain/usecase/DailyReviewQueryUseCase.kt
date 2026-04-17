package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import kotlin.random.Random

/**
 * Picks non-deterministic random-walk batches from the memo stream without materializing the
 * entire list in memory.
 */
class DailyReviewQueryUseCase(
    private val repository: MemoRepository,
) {
    suspend operator fun invoke(seed: Long = Random.Default.nextLong()): List<Memo> =
        loadMore(
            excludeIds = emptySet(),
            batchSize = DEFAULT_DAILY_REVIEW_LIMIT,
            seed = seed,
        )

    suspend fun loadMore(
        excludeIds: Set<String>,
        batchSize: Int,
        seed: Long = Random.Default.nextLong(),
    ): List<Memo> {
        val totalMemoCount = if (batchSize > 0) repository.getMemoCount() else 0
        if (batchSize <= 0 || totalMemoCount <= 0) {
            return emptyList()
        }

        val randomIndexCursor = RandomIndexCursor(totalMemoCount, Random(seed))
        val pageCache = HashMap<Int, List<Memo>>()
        val seenIds = excludeIds.toHashSet()
        val randomWalkBatch = ArrayList<Memo>(batchSize.coerceAtMost(totalMemoCount))

        while (randomIndexCursor.hasNext() && randomWalkBatch.size < batchSize) {
            val index = randomIndexCursor.next()
            val pageIndex = index / DAILY_REVIEW_PAGE_SIZE
            val pageOffset = pageIndex * DAILY_REVIEW_PAGE_SIZE
            val pageMemos =
                pageCache[pageIndex] ?: repository
                    .getMemosPage(limit = DAILY_REVIEW_PAGE_SIZE, offset = pageOffset)
                    .also { pageCache[pageIndex] = it }
            val memo = pageMemos.getOrNull(index - pageOffset) ?: continue

            if (seenIds.add(memo.id)) {
                randomWalkBatch += memo
            }
        }

        return randomWalkBatch
    }

    /**
     * Generates a random permutation lazily with a partial Fisher-Yates walk so callers can
     * stop once the requested batch has been filled.
     */
    private class RandomIndexCursor(
        private val populationSize: Int,
        private val random: Random,
    ) {
        private val swaps = HashMap<Int, Int>()
        private var nextPosition = 0

        fun hasNext(): Boolean = nextPosition < populationSize

        fun next(): Int {
            val currentPosition = nextPosition
            val randomPosition = random.nextInt(currentPosition, populationSize)
            val valueAtCurrentPosition = swaps[currentPosition] ?: currentPosition
            val valueAtRandomPosition = swaps[randomPosition] ?: randomPosition

            swaps[currentPosition] = valueAtRandomPosition
            swaps[randomPosition] = valueAtCurrentPosition
            nextPosition += 1
            return valueAtRandomPosition
        }
    }

    companion object {
        const val DEFAULT_DAILY_REVIEW_LIMIT = 20
        private const val DAILY_REVIEW_PAGE_SIZE = 64
    }
}
