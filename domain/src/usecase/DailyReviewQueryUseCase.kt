package com.lomo.domain.usecase

import com.lomo.domain.model.DailyReviewCollectionPage
import com.lomo.domain.model.DailyReviewCollectionSource
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoQueryRepository
import java.time.LocalDate
import kotlin.random.Random

/**
 * Picks non-deterministic random-walk batches from the memo stream without materializing the
 * entire list in memory.
 */
class DailyReviewQueryUseCase(
    private val repository: MemoQueryRepository,
) {
    suspend operator fun invoke(seed: Long = Random.Default.nextLong()): List<Memo> =
        loadPage(legacySource(seed = seed)).memos

    suspend fun loadMore(
        excludeIds: Set<String>,
        batchSize: Int,
        seed: Long = Random.Default.nextLong(),
    ): List<Memo> =
        loadPage(
            legacySource(
                seed = seed,
                excludeIds = excludeIds,
                pageSize = batchSize,
            ),
        ).memos

    suspend fun loadPage(source: DailyReviewCollectionSource): DailyReviewCollectionPage {
        val pageSize = source.pageSize
        val currentMemoCount = if (pageSize > 0) repository.getMemoCount() else 0
        if (pageSize <= 0) {
            return DailyReviewCollectionPage(memos = emptyList(), nextSource = source)
        }

        val candidateBoundary =
            source.candidateBoundary ?: repository.getDailyReviewCandidateBoundary()
        if (currentMemoCount <= 0 && source.randomCandidateChunkIds.isEmpty() && candidateBoundary == null) {
            return DailyReviewCollectionPage(memos = emptyList(), nextSource = source)
        }

        val observedMemoCount =
            source.observedMemoCount
                .takeIf { count -> count > 0 }
                ?: candidateBoundary?.observedCount?.takeIf { count -> count > 0 }
                ?: currentMemoCount
        val collectedPage =
            collectRandomCandidateMemos(
                source =
                    source.copy(
                        observedMemoCount = observedMemoCount,
                        candidateBoundary = candidateBoundary,
                    ),
                pageSize = pageSize,
                currentMemoCount = currentMemoCount,
            )
        val seenIds = collectedPage.seenIds
        val collectionBatch = collectedPage.memos
        var pageSource = collectedPage.source

        if (
            collectionBatch.size < pageSize &&
            (collectionBatch.isEmpty() || currentMemoCount > observedMemoCount)
        ) {
            val visibleUnseenPage =
                appendVisibleUnseenMemos(
                    totalMemoCount = currentMemoCount,
                    startOffset = pageSource.visibleUnseenOffset,
                    seenIds = seenIds,
                    collectionBatch = collectionBatch,
                    pageSize = pageSize,
                )
            pageSource =
                pageSource.copy(
                    visibleUnseenOffset = visibleUnseenPage.nextOffset,
                )
        }

        return DailyReviewCollectionPage(
            memos = collectionBatch,
            nextSource =
                pageSource.copy(
                    excludeIds = seenIds.toSet(),
                ),
        )
    }

    private suspend fun collectRandomCandidateMemos(
        source: DailyReviewCollectionSource,
        pageSize: Int,
        currentMemoCount: Int,
    ): CandidateCollectionPage {
        val seenIds = source.excludeIds.toMutableSet()
        val collectionBatch = ArrayList<Memo>(pageSize.coerceAtMost(currentMemoCount))
        var pageSource = source
        var shouldLoadNextChunk = collectionBatch.size < pageSize
        while (shouldLoadNextChunk) {
            val chunkPage =
                pageSource
                    .withReadyCandidateChunk()
                    ?.let { readySource ->
                        collectCandidateChunk(
                            source = readySource,
                            seenIds = seenIds,
                            collectionBatch = collectionBatch,
                            pageSize = pageSize,
                        )
                    }
            if (chunkPage == null) {
                shouldLoadNextChunk = false
            } else {
                pageSource = chunkPage.source
                shouldLoadNextChunk = chunkPage.exhaustedChunk && collectionBatch.size < pageSize
            }
        }
        return CandidateCollectionPage(
            memos = collectionBatch,
            seenIds = seenIds,
            source = pageSource,
        )
    }

    private suspend fun collectCandidateChunk(
        source: DailyReviewCollectionSource,
        seenIds: MutableSet<String>,
        collectionBatch: MutableList<Memo>,
        pageSize: Int,
    ): CandidateChunkPage {
        val randomIndexCursor = source.randomIndexCursor()
        while (randomIndexCursor.hasNext() && collectionBatch.size < pageSize) {
            val index = randomIndexCursor.next()
            val candidateId = source.randomCandidateChunkIds[index]
            if (seenIds.add(candidateId)) {
                repository.getMemoById(candidateId)?.let(collectionBatch::add)
            }
        }
        val exhaustedChunk = !randomIndexCursor.hasNext()
        val cursorSource =
            source.copy(
                excludeIds = seenIds.toSet(),
                randomIndexCursor = randomIndexCursor.position,
                randomIndexSwaps = randomIndexCursor.swapsSnapshot(),
            )
        return CandidateChunkPage(
            source = cursorSource.nextChunkSourceIfExhausted(exhaustedChunk),
            exhaustedChunk = exhaustedChunk,
        )
    }

    private fun DailyReviewCollectionSource.randomIndexCursor(): RandomIndexCursor =
        RandomIndexCursor(
            populationSize = randomCandidateChunkIds.size,
            seed = seed,
            chunkOffset = randomCandidateChunkOffset,
            initialPosition = randomIndexCursor,
            initialSwaps = randomIndexSwaps,
        )

    private fun DailyReviewCollectionSource.nextChunkSourceIfExhausted(
        exhaustedChunk: Boolean,
    ): DailyReviewCollectionSource =
        if (exhaustedChunk) {
            copy(
                randomIndexCursor = 0,
                randomIndexSwaps = emptyMap(),
                randomCandidateChunkIds = emptyList(),
                randomCandidateChunkOffset = candidatePageOffset,
            )
        } else {
            this
        }

    private suspend fun DailyReviewCollectionSource.withReadyCandidateChunk(): DailyReviewCollectionSource? {
        if (randomIndexCursor < randomCandidateChunkIds.size) {
            return this
        }
        val boundary = candidateBoundary ?: return null
        if (candidatePageOffset >= observedMemoCount) {
            return null
        }

        val chunkOffset = candidatePageOffset
        val chunkLimit = DAILY_REVIEW_PAGE_SIZE.coerceAtMost(observedMemoCount - chunkOffset)
        val candidatePage =
            repository.getDailyReviewCandidatePage(
                boundary = boundary,
                cursor = candidateCursor,
                limit = chunkLimit,
            )
        if (candidatePage.ids.isEmpty()) {
            return null
        }
        val ids = candidatePage.ids.distinct()

        return copy(
            randomIndexCursor = 0,
            randomIndexSwaps = emptyMap(),
            candidateCursor = candidatePage.nextCursor,
            randomCandidateChunkIds = ids,
            randomCandidateChunkOffset = chunkOffset,
            candidatePageOffset = (chunkOffset + ids.size).coerceAtMost(observedMemoCount),
        )
    }

    private suspend fun appendVisibleUnseenMemos(
        totalMemoCount: Int,
        startOffset: Int,
        seenIds: MutableSet<String>,
        collectionBatch: MutableList<Memo>,
        pageSize: Int,
    ): VisibleUnseenPage {
        var offset = startOffset.coerceAtLeast(0)
        var nextOffset = offset
        var scannedPageCount = 0
        while (
            offset < totalMemoCount &&
            collectionBatch.size < pageSize &&
            scannedPageCount < MAX_VISIBLE_UNSEEN_SCAN_PAGES
        ) {
            val pageMemos = repository.getMemosPage(limit = DAILY_REVIEW_PAGE_SIZE, offset = offset)
            if (pageMemos.isEmpty()) {
                return VisibleUnseenPage(nextOffset = totalMemoCount)
            }
            var pageIndex = 0
            while (pageIndex < pageMemos.size && collectionBatch.size < pageSize) {
                val memo = pageMemos[pageIndex]
                val processedOffset = offset + pageIndex + 1
                if (seenIds.add(memo.id)) {
                    collectionBatch += memo
                    nextOffset = processedOffset
                }
                pageIndex += 1
            }
            if (pageIndex == pageMemos.size) {
                nextOffset = offset + pageMemos.size
            }
            offset += pageMemos.size
            scannedPageCount += 1
        }
        return VisibleUnseenPage(
            nextOffset = nextOffset.coerceAtMost(totalMemoCount),
        )
    }

    private data class VisibleUnseenPage(
        val nextOffset: Int,
    )

    private data class CandidateCollectionPage(
        val memos: MutableList<Memo>,
        val seenIds: MutableSet<String>,
        val source: DailyReviewCollectionSource,
    )

    private data class CandidateChunkPage(
        val source: DailyReviewCollectionSource,
        val exhaustedChunk: Boolean,
    )

    /*
     * Generates a chunk-local random permutation lazily with a partial Fisher-Yates walk. Random
     * choices are derived from seed + chunk offset + position so continuation only needs the cursor
     * position and swap table instead of replaying all previous random draws.
     */
    private class RandomIndexCursor(
        private val populationSize: Int,
        private val seed: Long,
        private val chunkOffset: Int,
        initialPosition: Int,
        initialSwaps: Map<Int, Int>,
    ) {
        private val swaps = HashMap(initialSwaps)
        private var nextPosition = initialPosition.coerceIn(0, populationSize)

        val position: Int
            get() = nextPosition

        fun hasNext(): Boolean = nextPosition < populationSize

        fun swapsSnapshot(): Map<Int, Int> = swaps.toMap()

        fun next(): Int {
            val currentPosition = nextPosition
            val randomPosition = randomPosition(currentPosition)
            val valueAtCurrentPosition = swaps[currentPosition] ?: currentPosition
            val valueAtRandomPosition = swaps[randomPosition] ?: randomPosition

            swaps[currentPosition] = valueAtRandomPosition
            swaps[randomPosition] = valueAtCurrentPosition
            nextPosition += 1
            return valueAtRandomPosition
        }

        private fun randomPosition(currentPosition: Int): Int {
            val remaining = populationSize - currentPosition
            return currentPosition + Random(positionSeed(currentPosition)).nextInt(remaining)
        }

        private fun positionSeed(currentPosition: Int): Long =
            seed xor
                (chunkOffset.toLong() * RANDOM_CHUNK_OFFSET_SALT) xor
                (currentPosition.toLong() * RANDOM_POSITION_SALT)
    }

    private fun legacySource(
        seed: Long,
        excludeIds: Set<String> = emptySet(),
        pageSize: Int = DEFAULT_DAILY_REVIEW_LIMIT,
    ): DailyReviewCollectionSource =
        DailyReviewCollectionSource(
            sessionDate = LEGACY_SESSION_DATE,
            seed = seed,
            excludeIds = excludeIds,
            pageSize = pageSize,
        )

    companion object {
        const val DEFAULT_DAILY_REVIEW_LIMIT = DailyReviewCollectionSource.DEFAULT_PAGE_SIZE
        private const val DAILY_REVIEW_PAGE_SIZE = 64
        private const val MAX_VISIBLE_UNSEEN_SCAN_PAGES = 2
        private const val RANDOM_CHUNK_OFFSET_SALT = -7046029254386353131L
        private const val RANDOM_POSITION_SALT = -4417276706812531889L
        private val LEGACY_SESSION_DATE: LocalDate = LocalDate.of(1970, 1, 1)
    }
}
