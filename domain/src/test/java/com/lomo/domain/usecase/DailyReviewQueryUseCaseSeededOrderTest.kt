package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: DailyReviewQueryUseCase
 * - Behavior focus: seeded random-review ordering must stay stable within the same day so a persisted page index still points at the same memo ordering after re-entry.
 * - Observable outcomes: identical memo id ordering for repeated queries with the same seed.
 * - Red phase: Fails before the fix because the use case always reseeds from volatile time and exposes no way to replay the same random walk.
 * - Excludes: session date rollover, UI pager restoration, and repository paging correctness already covered elsewhere.
 */
class DailyReviewQueryUseCaseSeededOrderTest {
    private val repository: MemoRepository = mockk()
    private val useCase = DailyReviewQueryUseCase(repository)

    @Test
    fun `invoke returns the same memo order when called with the same seed`() =
        runTest {
            val memos = (0 until 30).map(::memo)
            stubPagedMemos(memos)

            val first = useCase(seed = 42L).map { it.id }
            val second = useCase(seed = 42L).map { it.id }

            assertEquals(first, second)
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
            dateKey = "2026_04_16",
        )
}
