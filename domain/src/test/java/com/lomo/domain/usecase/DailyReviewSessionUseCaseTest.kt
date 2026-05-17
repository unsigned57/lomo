package com.lomo.domain.usecase

import com.lomo.domain.model.DailyReviewSession
import com.lomo.domain.repository.DailyReviewSessionRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: DailyReviewSessionUseCase
 * - Behavior focus: same-day session reuse, cross-day reset, and persisted page updates for the active random-review session.
 * - Observable outcomes: returned session date, seed, page index, and repository writes.
 * - Red phase: Fails before the fix because random review has no persisted same-day session contract, so it cannot restore the previous page against a stable ordering.
 * - Excludes: UI pager rendering, DataStore key wiring, and memo query pagination.
 */
class DailyReviewSessionUseCaseTest : DomainFunSpec() {
    init {
        test("prepareSession reuses the existing same-day session") {
            runTest {
                        val today = LocalDate.of(2026, 4, 16)
                        val repository =
                            FakeDailyReviewSessionRepository(
                                initialSession = DailyReviewSession(date = today, seed = 42L, pageIndex = 3),
                            )
                        val useCase =
                            DailyReviewSessionUseCase(
                                repository = repository,
                                currentDateProvider = { today },
                                seedProvider = { 99L },
                            )

                        val session = useCase.prepareSession()

                        session shouldBe DailyReviewSession(date = today, seed = 42L, pageIndex = 3)
                        repository.lastSavedSession shouldBe null
                    }
        }
    }
    init {
        test("prepareSession resets page and seed when the saved session is from a previous day") {
            runTest {
                        val today = LocalDate.of(2026, 4, 16)
                        val repository =
                            FakeDailyReviewSessionRepository(
                                initialSession = DailyReviewSession(date = today.minusDays(1), seed = 42L, pageIndex = 7),
                            )
                        val useCase =
                            DailyReviewSessionUseCase(
                                repository = repository,
                                currentDateProvider = { today },
                                seedProvider = { 99L },
                            )

                        val session = useCase.prepareSession()

                        session shouldBe DailyReviewSession(date = today, seed = 99L, pageIndex = 0)
                        repository.lastSavedSession shouldBe session
                    }
        }
    }
    init {
        test("updateCurrentPage persists the active same-day seed with the new page index") {
            runTest {
                        val today = LocalDate.of(2026, 4, 16)
                        val repository = FakeDailyReviewSessionRepository()
                        val useCase =
                            DailyReviewSessionUseCase(
                                repository = repository,
                                currentDateProvider = { today },
                                seedProvider = { 99L },
                            )

                        useCase.updateCurrentPage(seed = 123L, pageIndex = 5)

                        repository.lastSavedSession shouldBe DailyReviewSession(date = today, seed = 123L, pageIndex = 5)
                    }
        }
    }

    private class FakeDailyReviewSessionRepository(
        initialSession: DailyReviewSession? = null,
    ) : DailyReviewSessionRepository {
        private var storedSession: DailyReviewSession? = initialSession
        var lastSavedSession: DailyReviewSession? = null
            private set

        override suspend fun getSession(): DailyReviewSession? = storedSession

        override suspend fun saveSession(session: DailyReviewSession) {
            storedSession = session
            lastSavedSession = session
        }
    }
}
