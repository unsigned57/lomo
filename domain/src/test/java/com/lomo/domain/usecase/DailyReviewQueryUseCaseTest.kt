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
import com.lomo.domain.testing.fakes.FakeMemoRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: DailyReviewQueryUseCase
 * - Behavior focus: random-walk batch sizing, unseen-only loading, and repository page-backed sampling.
 * - Observable outcomes: returned memo ids, batch size limits, and absence of duplicates from the excluded id set.
 * - TDD proof: Fails before the fix because DailyReviewQueryUseCase only exposes a deterministic day-seeded query and has no incremental unseen-batch API.
 * - Excludes: UI pagination behavior, Compose rendering, and repository storage implementation details.
 */

class DailyReviewQueryUseCaseTest : DomainFunSpec() {
    private val repository = FakeMemoRepository()
    private val useCase = DailyReviewQueryUseCase(repository)
    init {
        test("loadMore returns empty when batch size is non-positive") {
            runTest {
                        val result = useCase.loadMore(excludeIds = emptySet(), batchSize = 0)
                        (result.isEmpty()) shouldBe true
                    }
        }

        test("loadMore returns empty when no memos exist") {
            runTest {
                        repository.setMemos(emptyList())

                        val result = useCase.loadMore(excludeIds = emptySet(), batchSize = 10)

                        (result.isEmpty()) shouldBe true
                    }
        }

        test("loadMore excludes ids that were already seen") {
            runTest {
                        val memos = (0 until 20).map { index -> memo(index) }
                        repository.setMemos(memos)

                        val excludedIds = setOf("memo_1", "memo_3", "memo_5", "memo_7", "memo_9")
                        val result = useCase.loadMore(excludeIds = excludedIds, batchSize = 8)

                        result.size shouldBe 8
                        (result.map { it.id }.none(excludedIds::contains)) shouldBe true
                        result.map { it.id }.distinct().size shouldBe result.size
                        repository.getAllMemosListCallCount shouldBe 0
                    }
        }

        test("loadMore returns remaining unseen memos when batch exceeds unseen pool") {
            runTest {
                        val memos = (0 until 3).map { index -> memo(index) }
                        repository.setMemos(memos)

                        val result = useCase.loadMore(excludeIds = setOf("memo_0"), batchSize = 10)

                        result.map { it.id }.toSet() shouldBe setOf("memo_1", "memo_2")
                    }
        }

        test("default invoke keeps the initial random walk batch size") {
            runTest {
                        val memos = (0 until 30).map { index -> memo(index) }
                        repository.setMemos(memos)

                        val result = useCase()

                        result.size shouldBe DailyReviewQueryUseCase.DEFAULT_DAILY_REVIEW_LIMIT
                    }
        }
    }

    private fun memo(index: Int): Memo =
        Memo(
            id = "memo_$index",
            timestamp = index.toLong(),
            content = "content_$index",
            rawContent = "- 00:00:00 content_$index",
            dateKey = "2026_02_24",
        )
}
