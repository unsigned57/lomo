package com.lomo.domain.usecase

import com.lomo.domain.AppConfig
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

class CreateMemoUseCaseTest {
    private val repository: MemoRepository = mockk()
    private val useCase = CreateMemoUseCase(repository, MemoContentValidator())

    @Test
    fun `invoke saves memo when content is valid`() =
        runTest {
            coEvery { repository.saveMemo(any(), any()) } just Runs

            useCase("valid content")

            coVerify(exactly = 1) { repository.saveMemo("valid content", any()) }
        }

    @Test
    fun `invoke rejects blank content before repository call`() =
        runTest {
            val exception = runCatching { useCase(" ") }.exceptionOrNull()

            assertNotNull(exception)
            assertTrue(exception is IllegalArgumentException)
            assertEquals(MemoContentValidator.EMPTY_CONTENT_MESSAGE, (exception as IllegalArgumentException).message)
            coVerify(exactly = 0) { repository.saveMemo(any(), any()) }
        }

    @Test
    fun `invoke rejects oversized content before repository call`() =
        runTest {
            val content = "a".repeat(AppConfig.MAX_MEMO_LENGTH + 1)

            val exception = runCatching { useCase(content) }.exceptionOrNull()

            assertNotNull(exception)
            assertTrue(exception is IllegalArgumentException)
            assertEquals(MemoContentValidator.lengthExceededMessage(), (exception as IllegalArgumentException).message)
            coVerify(exactly = 0) { repository.saveMemo(any(), any()) }
        }
}
