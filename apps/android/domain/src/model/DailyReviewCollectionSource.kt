package com.lomo.domain.model

import java.time.LocalDate

/** Month order is followed by seeded random order inside each month; RANDOM ignores months. */
enum class DailyReviewSort { RECENT, PAST, RANDOM }

data class DailyReviewOptions(
    val sort: DailyReviewSort = DailyReviewSort.RECENT,
    val filter: MemoListFilter = MemoListFilter(),
) {
    init {
        require(!filter.hasSortOverride) { "Review ordering is owned by DailyReviewSort" }
        require(filter.startDate == null || filter.endDate == null || filter.startDate <= filter.endDate) {
            "Review start date must not follow end date"
        }
    }
}

/** One bounded continuation under a single native query identity. */
data class DailyReviewCollectionSource(
    val sessionDate: LocalDate,
    val seed: Long,
    val options: DailyReviewOptions = DailyReviewOptions(),
    val excludeIds: Set<String> = emptySet(),
    val cursor: String? = null,
    val exhausted: Boolean = false,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "Review page size must be between 1 and 200" }
    }
    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 200
        fun fromSession(session: DailyReviewSession, pageSize: Int = DEFAULT_PAGE_SIZE): DailyReviewCollectionSource =
            DailyReviewCollectionSource(session.date, session.seed, session.options, pageSize = pageSize)
    }
}

data class DailyReviewCollectionPage(val memos: List<Memo>, val nextSource: DailyReviewCollectionSource)

data class DailyReviewCandidatePage(val ids: List<String>, val nextCursor: String?)
