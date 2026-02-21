package com.lomo.domain.usecase

import com.lomo.domain.AppConfig
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.validation.MemoContentValidator
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMemoUseCaseTest {
    private val repository: MemoRepository = mockk()
    private val useCase = UpdateMemoUseCase(repository, MemoContentValidator())

    private val memo =
        Memo(
            id = "memo_1",
            timestamp = 1L,
            content = "old content",
            rawContent = "- 00:00:01 old content",
            date = "2026_02_21",
        )

    @Test
    fun `invoke updates memo when content is valid`() =
        runTest {
            coEvery { repository.updateMemo(any(), any()) } just Runs

            useCase(memo, "new content")

            coVerify(exactly = 1) { repository.updateMemo(memo, "new content") }
        }

    @Test
    fun `invoke rejects oversized content before repository call`() =
        runTest {
            val content = "a".repeat(AppConfig.MAX_MEMO_LENGTH + 1)
            val exception = runCatching { useCase(memo, content) }.exceptionOrNull()

            assertNotNull(exception)
            assertTrue(exception is IllegalArgumentException)
            assertEquals(MemoContentValidator.lengthExceededMessage(), (exception as IllegalArgumentException).message)
            coVerify(exactly = 0) { repository.updateMemo(any(), any()) }
        }
}
