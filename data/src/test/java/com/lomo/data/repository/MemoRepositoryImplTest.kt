package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var dao: MemoDao

    @MockK(relaxed = true)
    private lateinit var synchronizer: MemoSynchronizer

    private lateinit var repository: MemoRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repository =
            MemoRepositoryImpl(
                dao = dao,
                synchronizer = synchronizer,
                resolveMemoUpdateActionUseCase = ResolveMemoUpdateActionUseCase(),
            )
    }

    @Test
    fun `saveMemo delegates to synchronizer`() =
        runTest {
            coEvery { synchronizer.saveMemoAsync(any(), any()) } just runs
            repository.saveMemo("content", timestamp = 123L)
            coVerify(exactly = 1) { synchronizer.saveMemoAsync("content", 123L) }
        }

    @Test
    fun `saveMemo propagates synchronizer exception`() =
        runTest {
            coEvery {
                synchronizer.saveMemoAsync(any(), any())
            } throws IllegalStateException("sync failed")

            val thrown =
                runCatching {
                    repository.saveMemo("content", timestamp = 456L)
                }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException)
        }

    @Test
    fun `updateMemo routes blank content to delete path`() =
        runTest {
            val memo =
                Memo(
                    id = "memo-1",
                    timestamp = 1L,
                    content = "old",
                    rawContent = "- 10:00 old",
                    dateKey = "2026_02_01",
                )

            repository.updateMemo(memo, "   ")

            coVerify(exactly = 1) { synchronizer.deleteMemoAsync(memo) }
            coVerify(exactly = 0) { synchronizer.updateMemoAsync(any(), any()) }
        }

    @Test
    fun `searchMemosList CJK phrase uses bigram AND match query`() =
        runTest {
            val captured = slot<String>()
            every { dao.searchMemosByFtsFlow(capture(captured)) } returns flowOf(emptyList())
            every { dao.searchMemosFlow(any()) } returns flowOf(emptyList())

            repository.searchMemosList("苏格拉底").first()

            assertEquals("苏格* AND 格拉* AND 拉底*", captured.captured)
            verify(exactly = 1) { dao.searchMemosByFtsFlow(any()) }
            verify(exactly = 0) { dao.searchMemosFlow(any()) }
        }

    @Test
    fun `searchMemosList single CJK char keeps unigram query`() =
        runTest {
            val captured = slot<String>()
            every { dao.searchMemosByFtsFlow(capture(captured)) } returns flowOf(emptyList())

            repository.searchMemosList("苏").first()

            assertEquals("苏*", captured.captured)
            verify(exactly = 1) { dao.searchMemosByFtsFlow(any()) }
        }
}
