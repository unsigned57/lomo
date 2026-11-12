package com.lomo.domain.usecase

import com.lomo.domain.model.DailyReviewCollectionPage
import com.lomo.domain.model.DailyReviewCollectionSource
import com.lomo.domain.repository.MemoQueryRepository

/** Hydrates one bounded owner-ordered page; Kotlin never reshuffles an engine query. */
class DailyReviewQueryUseCase(private val repository: MemoQueryRepository) {
    suspend fun loadPage(source: DailyReviewCollectionSource): DailyReviewCollectionPage {
        if (source.exhausted) return DailyReviewCollectionPage(emptyList(), source)
        val page = repository.getDailyReviewPage(source.options, source.seed, source.cursor, source.pageSize)
        val seen = source.excludeIds.toMutableSet()
        val memos = page.ids.mapNotNull { id ->
            if (seen.add(id)) {
                // behavior-contract: loop-io-ok: one bounded candidate page; hydrate at most pageSize memos
                repository.getMemoById(id)
            } else {
                null
            }
        }
        return DailyReviewCollectionPage(
            memos,
            source.copy(cursor = page.nextCursor, exhausted = page.nextCursor == null, excludeIds = seen.toSet()),
        )
    }
}
