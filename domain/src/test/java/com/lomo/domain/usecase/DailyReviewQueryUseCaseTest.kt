package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import io.mockk.every
import io.mockk.mockk
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
            every { repository.getAllMemosList() } returns flowOf(emptyList())

            val result = useCase(limit = 10, seedDate = LocalDate.of(2026, 2, 24))

            assertTrue(result.isEmpty())
        }

    @Test
    fun `returns deterministic page for seed date`() =
        runTest {
            val memos = (0 until 20).map { index -> memo(index) }
            val seedDate = LocalDate.of(2026, 2, 24)
            every { repository.getAllMemosList() } returns flowOf(memos)

            val first = useCase(limit = 5, seedDate = seedDate)
            val second = useCase(limit = 5, seedDate = seedDate)

            assertEquals(first, second)
            assertEquals(5, first.size)
        }

    @Test
    fun `returns all memos when limit exceeds total`() =
        runTest {
            val memos = (0 until 3).map { index -> memo(index) }
            every { repository.getAllMemosList() } returns flowOf(memos)

            val result = useCase(limit = 10, seedDate = LocalDate.of(2026, 2, 24))

            assertEquals(memos, result)
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
