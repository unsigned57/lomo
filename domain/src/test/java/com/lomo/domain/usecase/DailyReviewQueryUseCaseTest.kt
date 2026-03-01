package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyReviewQueryUseCaseTest {
    private val repository: MemoRepository = mockk()
    private val useCase = DailyReviewQueryUseCase(repository)

    @Test
    fun `returns empty when limit is non-positive`() =
        runTest {
            val result = useCase(limit = 0, seedDate = LocalDate.of(2026, 2, 24))
            assertTrue(result.isEmpty())
        }

    @Test
    fun `returns empty when no memos`() =
        runTest {
            stubPagedMemos(emptyList())

            val result = useCase(limit = 10, seedDate = LocalDate.of(2026, 2, 24))

            assertTrue(result.isEmpty())
        }

    @Test
    fun `returns deterministic page for seed date`() =
        runTest {
            val memos = (0 until 20).map { index -> memo(index) }
            val seedDate = LocalDate.of(2026, 2, 24)
            stubPagedMemos(memos)

            val first = useCase(limit = 5, seedDate = seedDate)
            val second = useCase(limit = 5, seedDate = seedDate)

            assertEquals(first, second)
            assertEquals(5, first.size)
            assertEquals(5, first.map { it.id }.distinct().size)
            verify(exactly = 0) { repository.getAllMemosList() }
        }

    @Test
    fun `returns all memos when limit exceeds total`() =
        runTest {
            val memos = (0 until 3).map { index -> memo(index) }
            stubPagedMemos(memos)

            val result = useCase(limit = 10, seedDate = LocalDate.of(2026, 2, 24))

            assertEquals(memos, result)
        }

    @Test
    fun `default invoke uses domain daily review policy`() =
        runTest {
            val memos = (0 until 30).map { index -> memo(index) }
            stubPagedMemos(memos)

            val result = useCase()

            assertEquals(10, result.size)
        }

    private fun stubPagedMemos(memos: List<Memo>) {
        coEvery { repository.getMemoCount() } returns memos.size
        coEvery { repository.getMemosPage(any(), any()) } answers {
            val limit = firstArg<Int>()
            val offset = secondArg<Int>()
            memos.drop(offset).take(limit)
        }
        every { repository.getAllMemosList() } returns flowOf(memos)
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
