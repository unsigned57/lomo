package com.lomo.domain.usecase

import com.lomo.domain.model.DailyReviewSession
import com.lomo.domain.repository.DailyReviewSessionRepository
import java.time.LocalDate
import kotlin.random.Random

class DailyReviewSessionUseCase(
    private val repository: DailyReviewSessionRepository,
    private val currentDateProvider: () -> LocalDate = LocalDate::now,
    private val seedProvider: () -> Long = { Random.Default.nextLong() },
) {
    suspend fun prepareSession(): DailyReviewSession {
        val today = currentDateProvider()
        val existing = repository.getSession()
        val normalizedExisting =
            existing
                ?.takeIf { it.date == today }
                ?.let { session -> session.copy(pageIndex = session.pageIndex.coerceAtLeast(0)) }
        if (normalizedExisting != null) {
            if (normalizedExisting != existing) {
                repository.saveSession(normalizedExisting)
            }
            return normalizedExisting
        }

        val newSession =
            DailyReviewSession(
                date = today,
                seed = seedProvider(),
                pageIndex = 0,
            )
        repository.saveSession(newSession)
        return newSession
    }

    suspend fun updateCurrentPage(
        seed: Long,
        pageIndex: Int,
    ) {
        repository.saveSession(
            DailyReviewSession(
                date = currentDateProvider(),
                seed = seed,
                pageIndex = pageIndex.coerceAtLeast(0),
            ),
        )
    }
}
