package com.lomo.domain.usecase

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.domain.model.Memo
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: DailyReviewQueryUseCase
 * - Behavior focus: seeded random-review ordering must stay stable within the same day so a persisted page index still points at the same memo ordering after re-entry.
 * - Observable outcomes: identical memo id ordering for repeated queries with the same seed.
 * - TDD proof: Fails before the fix because the use case always reseeds from volatile time and exposes no way to replay the same random walk.
 * - Excludes: session date rollover, UI pager restoration, and repository paging correctness already covered elsewhere.
 */
class DailyReviewQueryUseCaseSeededOrderTest : DomainFunSpec() {
    private val repository = FakeMemoStore()
    private val useCase = DailyReviewQueryUseCase(com.lomo.domain.testing.fakes.FakeMemoQueryRepository(repository))
    init {
        test("invoke returns the same memo order when called with the same seed") {
            runTest {
                        val memos = (0 until 30).map(::memo)
                        repository.setMemos(memos)

                        val first = useCase(seed = 42L).map { it.id }
                        val second = useCase(seed = 42L).map { it.id }

                        second shouldBe first
                    }
        }
    }

    private fun memo(index: Int): Memo =
        Memo(
            id = "memo_$index",
            timestamp = index.toLong(),
            content = "content_$index",
            rawContent = "- 00:00:00 content_$index",
            dateKey = "2026_04_16",
        )
}
