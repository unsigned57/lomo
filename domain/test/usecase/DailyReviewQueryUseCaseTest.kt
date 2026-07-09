package com.lomo.domain.usecase

/*
 * Behavior Contract:
 * - Unit under test: DailyReviewQueryUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: page Daily Review as a typed memo collection source with bounded candidate chunks, stable memo identity, and random-walk continuation.
 *
 * Scenarios:
 * - Given a non-positive page size, when a page is requested, then no memos are returned.
 * - Given no memos exist, when a page is requested, then the collection page is empty.
 * - Given a session source with excluded ids, when a page is requested, then excluded ids never appear.
 * - Given the same session source and excluded ids, when a page is requested repeatedly, then memo ordering is deterministic.
 * - Given a large visible collection, when the first page is requested, then only bounded repository pages are read into the candidate chunk.
 * - Given more than one candidate chunk exists, when a new head memo arrives before the candidate phase finishes, then the new memo is not returned until the original boundary is exhausted.
 * - Given additions and deletions keep the same collection size, when a later candidate page loads, then new ids outside the original boundary are not admitted into the candidate phase.
 * - Given the backing collection grows after a session page, when the candidate snapshot is exhausted, then new visible unseen memos are returned deterministically.
 * - Given a seen memo is deleted and the collection grows, when the candidate snapshot is exhausted, then the deleted seen id remains excluded and the new unseen memo can append.
 * - Given a memo disappears after the first page, when the next page is loaded from the returned source, then the disappeared memo stays excluded for that session.
 * - Given visible memo order changes after the first page, when the next source loads, then the random walk follows the frozen memo id chunk and does not admit new ids early.
 * - Given a returned source, when the next page loads, then the persisted random cursor state advances instead of replaying from the seed head.
 * - Given an initial source, when a page returns memos, then the next source advances the random chunk cursor and accumulates seen ids.
 * - Given deterministic visible-unseen append fills before a repository page ends, when the next page loads, then unprocessed ids from the same repository page are not skipped.
 * - Given deterministic visible-unseen append sees mostly seen ids, when one page is loaded, then repository page scanning is bounded and the cursor advances even if no ids append.
 *
 * Observable outcomes:
 * - Returned memo ids, next source sessionDate, seed, excludeIds, randomIndexCursor, randomIndexSwaps, randomCandidateChunkIds, candidatePageOffset, observedMemoCount, pageSize, and repository call counters.
 *
 * TDD proof:
 * - RED command: `./kotlin test --include-classes='com.lomo.domain.usecase.DailyReviewQueryUseCaseTest'`.
 * - RED symptom: `:domain:compileTestKotlin FAILED` with unresolved `randomCandidateChunkIds`, `randomIndexSwaps`, and `candidatePageOffset` fields before the bounded chunk source was implemented.
 * - GREEN command: same targeted domain command passes after the collection source freezes only bounded candidate chunks, persists cursor state, and scans visible unseen ids only after the snapshot chunks are exhausted.
 *
 * Excludes:
 * - UI pager rendering, repository storage internals, and full MemoCollectionPresenter integration.
 */

import com.lomo.domain.model.DailyReviewCollectionSource
import com.lomo.domain.model.Memo
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoStore
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlinx.coroutines.test.runTest

class DailyReviewQueryUseCaseTest : DomainFunSpec() {
    private val repository = FakeMemoStore()
    private val useCase = DailyReviewQueryUseCase(com.lomo.domain.testing.fakes.FakeMemoQueryRepository(repository))

    init {
        test("given non-positive page size when requesting a collection page then returns empty page") {
            runTest {
                val result = useCase.loadPage(source(pageSize = 0))

                result.memos shouldBe emptyList()
                result.nextSource shouldBe source(pageSize = 0)
            }
        }

        test("given no memos when requesting a collection page then returns empty page") {
            runTest {
                repository.setMemos(emptyList())

                val result = useCase.loadPage(source(pageSize = 10))

                result.memos shouldBe emptyList()
                result.nextSource shouldBe source(pageSize = 10)
            }
        }

        test("given excluded ids when requesting a collection page then excluded ids never return") {
            runTest {
                val memos = (0 until 20).map { index -> memo(index) }
                repository.setMemos(memos)

                val excludedIds = setOf("memo_1", "memo_3", "memo_5", "memo_7", "memo_9")
                val result = useCase.loadPage(source(excludeIds = excludedIds, pageSize = 8))

                result.memos.size shouldBe 8
                result.memos.map { it.id }.none(excludedIds::contains) shouldBe true
                result.memos.map { it.id }.distinct().size shouldBe result.memos.size
                result.nextSource.excludeIds.containsAll(excludedIds) shouldBe true
                result.nextSource.excludeIds.containsAll(result.memos.map { it.id }) shouldBe true
                repository.getAllMemosListCallCount shouldBe 0
            }
        }

        test("given same session source and exclude ids when requesting pages then ordering is deterministic") {
            runTest {
                val memos = (0 until 40).map { index -> memo(index) }
                repository.setMemos(memos)
                val dailyReviewSource =
                    source(
                        seed = 42L,
                        excludeIds = setOf("memo_1", "memo_3", "memo_5"),
                        pageSize = 10,
                    )

                val first = useCase.loadPage(dailyReviewSource)
                val second = useCase.loadPage(dailyReviewSource)

                second.memos.map { it.id } shouldBe first.memos.map { it.id }
                second.nextSource shouldBe first.nextSource
            }
        }

        test("given large collection when requesting first page then only bounded candidate pages are read") {
            runTest {
                val memos = (0 until 1_000).map { index -> memo(index) }
                repository.setMemos(memos)

                val result = useCase.loadPage(source(seed = 11L, pageSize = 5))

                result.memos.size shouldBe 5
                repository.pageRequests shouldBe listOf(FakeMemoStore.PageRequest(limit = 64, offset = 0))
                result.nextSource.randomCandidateChunkIds shouldBe memos.take(64).map { memo -> memo.id }
                result.nextSource.candidatePageOffset shouldBe 64
                result.nextSource.observedMemoCount shouldBe 1_000
            }
        }

        test("given more than one candidate chunk when new head arrives then original candidate boundary stays stable") {
            runTest {
                val memos = (0 until 130).map { index -> memo(index) }
                val newHeadMemo = memo(999)
                repository.setMemos(memos)
                val firstPage = useCase.loadPage(source(seed = 31L, pageSize = 64))

                repository.setMemos(listOf(newHeadMemo) + memos)
                val secondPage = useCase.loadPage(firstPage.nextSource)

                secondPage.memos.map { memo -> memo.id }.contains(newHeadMemo.id) shouldBe false
                secondPage.nextSource.candidatePageOffset shouldBe 128
                secondPage.nextSource.observedMemoCount shouldBe 130
            }
        }

        test("given net-zero add and delete before candidate phase finishes then new id is not admitted") {
            runTest {
                val memos = (0 until 130).map { index -> memo(index) }
                val insertedMemo = memo(900)
                repository.setMemos(memos)
                val firstPage = useCase.loadPage(source(seed = 32L, pageSize = 64))
                val currentAfterNetZeroChange =
                    memos.take(70) + insertedMemo + memos.drop(70).dropLast(1)

                repository.setMemos(currentAfterNetZeroChange)
                val secondPage = useCase.loadPage(firstPage.nextSource)

                secondPage.memos.map { memo -> memo.id }.contains(insertedMemo.id) shouldBe false
                secondPage.nextSource.observedMemoCount shouldBe 130
            }
        }

        test("given returned source when loading next page then chunk random cursor continues without replay state loss") {
            runTest {
                val memos = (0 until 100).map { index -> memo(index) }
                repository.setMemos(memos)

                val firstPage = useCase.loadPage(source(seed = 22L, pageSize = 4))
                val firstCursor = firstPage.nextSource.randomIndexCursor
                val firstSwaps = firstPage.nextSource.randomIndexSwaps
                val secondPage = useCase.loadPage(firstPage.nextSource)

                firstCursor shouldBe 4
                firstSwaps.isNotEmpty() shouldBe true
                secondPage.nextSource.randomIndexCursor shouldBe 8
                firstSwaps.forEach { (index, value) ->
                    secondPage.nextSource.randomIndexSwaps[index] shouldBe value
                }
                secondPage.nextSource.randomCandidateChunkIds shouldBe firstPage.nextSource.randomCandidateChunkIds
                repository.pageRequests shouldBe listOf(FakeMemoStore.PageRequest(limit = 64, offset = 0))
            }
        }

        test("given backing collection grows after first page when loading next source then new unseen memo returns deterministically after snapshot") {
            runTest {
                val firstBatch = listOf(memo(0), memo(1))
                val addedMemo = memo(2)
                repository.setMemos(firstBatch)
                val firstPage = useCase.loadPage(source(seed = 1L, pageSize = 20))

                repository.setMemos(firstBatch + addedMemo)
                val nextPage = useCase.loadPage(firstPage.nextSource)

                firstPage.memos.map { it.id }.toSet() shouldBe setOf("memo_0", "memo_1")
                nextPage.memos.map { it.id } shouldBe listOf("memo_2")
                nextPage.nextSource.excludeIds shouldBe setOf("memo_0", "memo_1", "memo_2")
                nextPage.nextSource.randomCandidateChunkIds shouldBe emptyList()
                nextPage.nextSource.observedMemoCount shouldBe 2
            }
        }

        test("given seen memo is deleted and collection grows when loading next source then deleted id stays excluded") {
            runTest {
                val firstBatch = listOf(memo(0), memo(1))
                repository.setMemos(firstBatch)
                val firstPage = useCase.loadPage(source(seed = 1L, pageSize = 20))

                repository.setMemos(listOf(memo(1), memo(2)))
                val nextPage = useCase.loadPage(firstPage.nextSource)

                nextPage.memos.map { it.id } shouldBe listOf("memo_2")
                nextPage.nextSource.excludeIds.contains("memo_0") shouldBe true
                nextPage.nextSource.excludeIds.contains("memo_1") shouldBe true
                nextPage.nextSource.excludeIds.contains("memo_2") shouldBe true
                nextPage.nextSource.randomCandidateChunkIds shouldBe emptyList()
                nextPage.nextSource.observedMemoCount shouldBe 2
            }
        }

        test("given visible order changes when loading next source then random walk follows frozen memo ids") {
            runTest {
                val memos = (0 until 8).map { index -> memo(index) }
                val insertedMemo = memo(99)
                repository.setMemos(memos)
                val firstPage = useCase.loadPage(source(seed = 42L, pageSize = 3))
                val expectedNextIds = useCase.loadPage(firstPage.nextSource).memos.map { memo -> memo.id }

                repository.setMemos(listOf(insertedMemo) + memos.reversed())
                val movedNextPage = useCase.loadPage(firstPage.nextSource)

                firstPage.nextSource.randomCandidateChunkIds shouldBe memos.map { memo -> memo.id }
                movedNextPage.memos.map { memo -> memo.id } shouldBe expectedNextIds
                movedNextPage.memos.map { memo -> memo.id }.contains(insertedMemo.id) shouldBe false
                movedNextPage.nextSource.randomCandidateChunkIds shouldBe firstPage.nextSource.randomCandidateChunkIds
            }
        }

        test("given a deleted memo after first page when loading next source then deleted seen id stays excluded") {
            runTest {
                val memos = (0 until 8).map { index -> memo(index) }
                repository.setMemos(memos)
                val firstPage = useCase.loadPage(source(seed = 42L, pageSize = 3))
                val deletedSeenId = firstPage.memos.first().id
                repository.setMemos(memos.filterNot { memo -> memo.id == deletedSeenId })

                val nextPage = useCase.loadPage(firstPage.nextSource)

                nextPage.memos.map { it.id }.contains(deletedSeenId) shouldBe false
                nextPage.nextSource.excludeIds.contains(deletedSeenId) shouldBe true
            }
        }

        test("given a collection source when page returns memos then next source advances cursor and seen ids") {
            runTest {
                val memos = (0 until 30).map { index -> memo(index) }
                repository.setMemos(memos)
                val initialSource =
                    source(
                        sessionDate = LocalDate.of(2026, 5, 22),
                        seed = 99L,
                        randomCandidateChunkIds = memos.take(12).map { memo -> memo.id },
                        candidatePageOffset = 12,
                        observedMemoCount = memos.size,
                        pageSize = 6,
                    )

                val result = useCase.loadPage(initialSource)

                result.memos.size shouldBe 6
                result.nextSource.sessionDate shouldBe initialSource.sessionDate
                result.nextSource.seed shouldBe initialSource.seed
                result.nextSource.pageSize shouldBe initialSource.pageSize
                result.nextSource.randomIndexCursor shouldBe 6
                result.nextSource.randomIndexSwaps.isNotEmpty() shouldBe true
                result.nextSource.observedMemoCount shouldBe initialSource.observedMemoCount
                result.nextSource.randomCandidateChunkIds shouldBe initialSource.randomCandidateChunkIds
                result.nextSource.candidatePageOffset shouldBe initialSource.candidatePageOffset
                result.nextSource.excludeIds shouldBe initialSource.excludeIds + result.memos.map { it.id }
            }
        }

        test("given page size exceeds unseen pool when requesting a page then returns remaining unseen memos") {
            runTest {
                val memos = (0 until 3).map { index -> memo(index) }
                repository.setMemos(memos)

                val result = useCase.loadPage(source(excludeIds = setOf("memo_0"), pageSize = 10))

                result.memos.map { it.id }.toSet() shouldBe setOf("memo_1", "memo_2")
            }
        }

        test("given deterministic append fills mid repository page when loading next page then unprocessed ids are not skipped") {
            runTest {
                val initialMemos = listOf(memo(0), memo(1))
                val appendedMemos = (2 until 70).map { index -> memo(index) }
                repository.setMemos(initialMemos)
                val firstPage = useCase.loadPage(source(seed = 1L, pageSize = 20))
                repository.setMemos(initialMemos + appendedMemos)

                val secondPage = useCase.loadPage(firstPage.nextSource.copy(pageSize = 5))
                val thirdPage = useCase.loadPage(secondPage.nextSource)

                secondPage.memos.map { memo -> memo.id } shouldBe (2 until 7).map { index -> "memo_$index" }
                thirdPage.memos.map { memo -> memo.id } shouldBe (7 until 12).map { index -> "memo_$index" }
            }
        }

        test("given terminal deterministic append when most ids are seen then scans are bounded and cursor advances") {
            runTest {
                val memos = (0 until 1_000).map { index -> memo(index) }
                repository.setMemos(memos)
                val terminalCandidateSource =
                    source(
                        excludeIds = memos.map { memo -> memo.id }.toSet(),
                        observedMemoCount = memos.size,
                        candidatePageOffset = memos.size,
                        visibleUnseenOffset = 0,
                        pageSize = 5,
                    )

                val result = useCase.loadPage(terminalCandidateSource)

                result.memos shouldBe emptyList()
                repository.pageRequests.size shouldBe 2
                result.nextSource.visibleUnseenOffset shouldBe 128
            }
        }
    }

    private fun source(
        sessionDate: LocalDate = LocalDate.of(2026, 4, 16),
        seed: Long = 7L,
        excludeIds: Set<String> = emptySet(),
        randomIndexCursor: Int = 0,
        randomIndexSwaps: Map<Int, Int> = emptyMap(),
        randomCandidateChunkIds: List<String> = emptyList(),
        randomCandidateChunkOffset: Int = 0,
        candidatePageOffset: Int = 0,
        observedMemoCount: Int = 0,
        visibleUnseenOffset: Int = 0,
        pageSize: Int = DailyReviewQueryUseCase.DEFAULT_DAILY_REVIEW_LIMIT,
    ): DailyReviewCollectionSource =
        DailyReviewCollectionSource(
            sessionDate = sessionDate,
            seed = seed,
            excludeIds = excludeIds,
            randomIndexCursor = randomIndexCursor,
            randomIndexSwaps = randomIndexSwaps,
            randomCandidateChunkIds = randomCandidateChunkIds,
            randomCandidateChunkOffset = randomCandidateChunkOffset,
            candidatePageOffset = candidatePageOffset,
            observedMemoCount = observedMemoCount,
            visibleUnseenOffset = visibleUnseenOffset,
            pageSize = pageSize,
        )

    private fun memo(index: Int): Memo =
        Memo(
            id = "memo_$index",
            timestamp = index.toLong(),
            content = "content_$index",
            rawContent = "- 00:00:00 content_$index",
            dateKey = "2026_02_24",
        )
}
