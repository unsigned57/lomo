package com.lomo.domain.model

import java.time.LocalDate

data class DailyReviewCollectionSource(
    val sessionDate: LocalDate,
    val seed: Long,
    val excludeIds: Set<String> = emptySet(),
    val randomIndexCursor: Int = 0,
    val randomIndexSwaps: Map<Int, Int> = emptyMap(),
    val observedMemoCount: Int = 0,
    val candidateBoundary: DailyReviewCandidateBoundary? = null,
    val candidateCursor: DailyReviewCandidateCursor? = null,
    val randomCandidateChunkIds: List<String> = emptyList(),
    val randomCandidateChunkOffset: Int = 0,
    val candidatePageOffset: Int = 0,
    val visibleUnseenOffset: Int = 0,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 20

        fun fromSession(
            session: DailyReviewSession,
            pageSize: Int = DEFAULT_PAGE_SIZE,
        ): DailyReviewCollectionSource =
            DailyReviewCollectionSource(
                sessionDate = session.date,
                seed = session.seed,
                pageSize = pageSize,
            )
    }
}

data class DailyReviewCollectionPage(
    val memos: List<Memo>,
    val nextSource: DailyReviewCollectionSource,
)

data class DailyReviewCandidateBoundary(
    val isPinned: Boolean,
    val timestamp: Long,
    val id: String,
    val token: String = "",
    val observedCount: Int = 0,
)

data class DailyReviewCandidateCursor(
    val isPinned: Boolean,
    val timestamp: Long,
    val id: String,
    val token: String = "",
    val position: Int = 0,
)

data class DailyReviewCandidatePage(
    val ids: List<String>,
    val nextCursor: DailyReviewCandidateCursor?,
)
