package com.lomo.domain.usecase

import com.lomo.domain.model.DailyReviewCollectionSource
import com.lomo.domain.model.DailyReviewOptions
import com.lomo.domain.model.DailyReviewSort
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoQueryRepository
import com.lomo.domain.testing.fakes.FakeMemoStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.time.LocalDate

/*
 * Behavior Contract:
 * Capability: bounded owner-ordered review hydration; owner: domain adapter; priority: P1.
 * Scenarios:
 * Given a query/seed and cursor, when pages load, then they preserve owner order, exclude seen identities, stop at
 * exhaustion and propagate a stale query instead of mixing two snapshots. Invalid sizes reject.
 * Production scope: apps/android/domain/src/usecase/DailyReviewQueryUseCase.kt
 * Production scope: apps/android/domain/src/model/DailyReviewCollectionSource.kt
 * TDD proof: Rust review_query_contract RED exposed pin/chronology overriding review order;
 * this adapter suite verifies propagation and bounded hydration under GREEN.
 * Test Change Justification:
 * Reason category: user-requested review query semantics and ownership correction.
 * Old behavior/assertion being replaced: Kotlin 64-row shuffle chunks and a second unseen scan.
 * Why old assertion is no longer correct: chunks mix month boundaries and cannot globally shuffle;
 * native query cursors already reject changed publications instead of promising frozen fake IDs.
 * Coverage preserved by: bounded reads, exclusion, deterministic continuation, terminal pages,
 * filters, seed replay, and explicit stale-snapshot rejection here and in review_query_contract.
 * Why this is not fitting the test to the implementation: month/random order is asserted against
 * fixed vectors in the actual Rust store; this suite asserts only the adapter's public outcomes.
 * Excludes: Compose and persistence; no whole-list production fallback is allowed.
 * Observable outcomes: bounded ordered page results, exhausted cursors, excluded IDs and stale-query errors.
 */
class DailyReviewQueryUseCaseTest : DomainFunSpec() {
    init {
        test("given an invalid page size when constructing a source then it rejects before reading") {
            shouldThrow<IllegalArgumentException> { source(pageSize = 0) }
            shouldThrow<IllegalArgumentException> { source(pageSize = 201) }
        }
        test("given no memos when loading then the source becomes exhausted") {
            runTest {
                val store = FakeMemoStore()
                val page = DailyReviewQueryUseCase(FakeMemoQueryRepository(store)).loadPage(source())
                page.memos shouldBe emptyList()
                page.nextSource.exhausted shouldBe true
            }
        }
        test("given a large collection when loading then one bounded page is read and cursor continues") {
            runTest {
                val store = FakeMemoStore().apply { setMemos((0 until 1000).map(::memo)) }
                val repo = FakeMemoQueryRepository(store)
                val useCase = DailyReviewQueryUseCase(repo)
                val first = useCase.loadPage(source(pageSize = 7))
                val second = useCase.loadPage(first.nextSource)
                first.memos.size shouldBe 7
                second.memos.size shouldBe 7
                first.memos.map { it.id }.intersect(second.memos.map { it.id }.toSet()) shouldBe emptySet()
                repo.reviewRequests.map { it.limit } shouldBe listOf(7, 7)
            }
        }
        test("given excluded IDs and a terminal source when loading then seen memos never return or rescan") {
            runTest {
                val store = FakeMemoStore().apply { setMemos((0 until 3).map(::memo)) }
                val repo = FakeMemoQueryRepository(store)
                val useCase = DailyReviewQueryUseCase(repo)
                val page = useCase.loadPage(source().copy(excludeIds = setOf("memo-0")))
                page.memos.map { it.id }.toSet() shouldBe setOf("memo-1", "memo-2")
                useCase.loadPage(page.nextSource).memos shouldBe emptyList()
                repo.reviewRequests.size shouldBe 1
            }
        }
        test("given a changed collection when resuming then stale query state surfaces") {
            runTest {
                val store = FakeMemoStore().apply { setMemos((0 until 5).map(::memo)) }
                val useCase = DailyReviewQueryUseCase(FakeMemoQueryRepository(store))
                val first = useCase.loadPage(source(pageSize = 2))
                store.setMemos((0 until 6).map(::memo))
                shouldThrow<IllegalArgumentException> { useCase.loadPage(first.nextSource) }
            }
        }
        test("given query options when reading then they reach the owner and constrain the result") {
            runTest {
                val store = FakeMemoStore().apply {
                    setMemos(
                        listOf(memo(1), memo(2).copy(imageUrls = listOf("image.png"))),
                    )
                }
                val repo = FakeMemoQueryRepository(store)
                val options = DailyReviewOptions(DailyReviewSort.PAST, MemoListFilter(hasAttachment = true))
                val page = DailyReviewQueryUseCase(repo).loadPage(source().copy(options = options))
                page.memos.map { it.id } shouldBe listOf("memo-2")
                repo.reviewRequests.single().options shouldBe options
            }
        }
    }
}
private fun source(pageSize: Int = 20) = DailyReviewCollectionSource(LocalDate.of(2026, 1, 1), 7, pageSize = pageSize)
private fun memo(index: Int) = Memo(
    id = "memo-$index",
    timestamp = index.toLong(),
    content = "note",
    rawContent = "note",
    dateKey = "1970_01_01",
)
