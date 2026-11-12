package com.lomo.domain.usecase

import com.lomo.domain.model.DailyReviewCollectionSource
import com.lomo.domain.model.Memo
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoQueryRepository
import com.lomo.domain.testing.fakes.FakeMemoStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.time.LocalDate

/*
 * Behavior Contract:
 * - Capability: re-entering a daily review replays the same seed and owner-ordered first page.
 * - Owning layer: domain adapter; priority: P1.
 * Scenarios:
 * - Given the same collection and seed, when loading twice, then memo identity order is equal.
 * - Production scope: apps/android/domain/src/usecase/DailyReviewQueryUseCase.kt
 * - Observable outcomes: identical public page results; native ordering is covered by store tests.
 * - TDD proof: the pre-seeded implementation changed order on each query; this regression keeps
 *   that replay assertion while adapting to bounded owner-ordered pages.
 * - Test Change Justification: the obsolete whole-review invoke API became loadPage(source).
 *   The same-seed equality assertion is preserved, without asserting private shuffle details.
 * - Excludes: session rollover, Compose pager restoration and statistical randomness.
 * Test Change Justification:
 * Reason category: bounded owner-ordered query migration.
 * Old behavior/assertion being replaced: The obsolete whole-review invocation was replaced with loadPage(source); seed equality remains.
 * Why old assertion is no longer correct: existing outcome assertions remain valid and retained.
 * Coverage preserved by: Both loads must return the same public memo order for the same collection and seed.
 * Why this is not fitting the test to the implementation: no expected product outcome is weakened.
 */
class DailyReviewQueryUseCaseSeededOrderTest : DomainFunSpec() {
    private val repository = FakeMemoStore()
    private val useCase = DailyReviewQueryUseCase(FakeMemoQueryRepository(repository))

    init {
        test("given the same review seed when loading twice then memo order repeats") {
            runTest {
                repository.setMemos((0 until 30).map(::memo))
                val source = DailyReviewCollectionSource(LocalDate.of(2026, 4, 16), 42L)

                val first = useCase.loadPage(source).memos.map { it.id }
                val second = useCase.loadPage(source).memos.map { it.id }

                second shouldBe first
            }
        }
    }

    private fun memo(index: Int): Memo = Memo(
        id = "memo_$index",
        timestamp = index.toLong(),
        content = "content_$index",
        rawContent = "- 00:00:00 content_$index",
        dateKey = "2026_04_16",
    )
}
