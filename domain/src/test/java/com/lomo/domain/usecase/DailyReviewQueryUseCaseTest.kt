package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: DailyReviewQueryUseCase
 * - Behavior focus: random-walk batch sizing, unseen-only loading, and repository page-backed sampling.
 * - Observable outcomes: returned memo ids, batch size limits, and absence of duplicates from the excluded id set.
 * - Red phase: Fails before the fix because DailyReviewQueryUseCase only exposes a deterministic day-seeded query and has no incremental unseen-batch API.
 * - Excludes: UI pagination behavior, Compose rendering, and repository storage implementation details.
 */

class DailyReviewQueryUseCaseTest {
    private val repository: MemoRepository = mockk()
    private val useCase = DailyReviewQueryUseCase(repository)

    @Test
    fun `loadMore returns empty when batch size is non-positive`() =
        runTest {
            val result = useCase.loadMore(excludeIds = emptySet(), batchSize = 0)
            assertTrue(result.isEmpty())
        }

    @Test
    fun `loadMore returns empty when no memos exist`() =
        runTest {
            stubPagedMemos(emptyList())

            val result = useCase.loadMore(excludeIds = emptySet(), batchSize = 10)

            assertTrue(result.isEmpty())
        }

    @Test
    fun `loadMore excludes ids that were already seen`() =
        runTest {
            val memos = (0 until 20).map { index -> memo(index) }
            stubPagedMemos(memos)

            val excludedIds = setOf("memo_1", "memo_3", "memo_5", "memo_7", "memo_9")
            val result = useCase.loadMore(excludeIds = excludedIds, batchSize = 8)

            assertEquals(8, result.size)
            assertTrue(result.map { it.id }.none(excludedIds::contains))
            assertEquals(result.size, result.map { it.id }.distinct().size)
            verify(exactly = 0) { repository.getAllMemosList() }
        }

    @Test
    fun `loadMore returns remaining unseen memos when batch exceeds unseen pool`() =
        runTest {
            val memos = (0 until 3).map { index -> memo(index) }
            stubPagedMemos(memos)

            val result = useCase.loadMore(excludeIds = setOf("memo_0"), batchSize = 10)

            assertEquals(setOf("memo_1", "memo_2"), result.map { it.id }.toSet())
        }

    @Test
    fun `default invoke keeps the initial random walk batch size`() =
        runTest {
            val memos = (0 until 30).map { index -> memo(index) }
            stubPagedMemos(memos)

            val result = useCase()

            assertEquals(DailyReviewQueryUseCase.DEFAULT_DAILY_REVIEW_LIMIT, result.size)
        }

    private fun stubPagedMemos(memos: List<Memo>) {
        coEvery { repository.getMemoCount() } returns memos.size
        coEvery { repository.getMemosPage(any(), any()) } answers {
            val limit = firstArg<Int>()
            val offset = secondArg<Int>()
            memos.drop(offset).take(limit)
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
