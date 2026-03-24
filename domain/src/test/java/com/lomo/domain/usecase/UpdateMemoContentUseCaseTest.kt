package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: UpdateMemoContentUseCase
 * - Behavior focus: update-vs-trash branching and validation gate before mutation.
 * - Observable outcomes: invoked collaborator path, call ordering, and exception propagation.
 * - Excludes: repository implementation internals, parser behavior, and UI rendering.
 */
class UpdateMemoContentUseCaseTest {
    private val repository: MemoRepository = mockk()
    private val validator: ValidateMemoContentUseCase = mockk()
    private val resolveMemoUpdateActionUseCase: ResolveMemoUpdateActionUseCase = mockk()
    private val deleteMemoUseCase: DeleteMemoUseCase = mockk()
    private val useCase =
        UpdateMemoContentUseCase(
            repository = repository,
            validator = validator,
            resolveMemoUpdateActionUseCase = resolveMemoUpdateActionUseCase,
            deleteMemoUseCase = deleteMemoUseCase,
        )

    private val memo =
        Memo(
            id = "memo-1",
            timestamp = 1L,
            content = "old-content",
            rawContent = "- 10:00 old-content",
            dateKey = "2026_03_24",
        )

    @Test
    fun `blank content delegates to trash flow without validation or update`() =
        runTest {
            every { resolveMemoUpdateActionUseCase.invoke("   ") } returns MemoUpdateAction.MOVE_TO_TRASH
            coEvery { deleteMemoUseCase.invoke(memo) } returns Unit

            useCase(memo, "   ")

            coVerify(exactly = 1) { deleteMemoUseCase.invoke(memo) }
            coVerify(exactly = 0) { validator.requireValidForUpdate(any()) }
            coVerify(exactly = 0) { repository.updateMemo(any(), any()) }
        }

    @Test
    fun `update flow validates first then persists updated content`() =
        runTest {
            every { resolveMemoUpdateActionUseCase.invoke("new-content") } returns MemoUpdateAction.UPDATE_CONTENT
            coEvery { validator.requireValidForUpdate("new-content") } returns Unit
            coEvery { repository.updateMemo(memo, "new-content") } returns Unit

            useCase(memo, "new-content")

            coVerifyOrder {
                validator.requireValidForUpdate("new-content")
                repository.updateMemo(memo, "new-content")
            }
            coVerify(exactly = 0) { deleteMemoUseCase.invoke(any()) }
        }

    @Test
    fun `validation failure is propagated and update is skipped`() =
        runTest {
            val validationError = IllegalArgumentException("content too long")
            every { resolveMemoUpdateActionUseCase.invoke("invalid") } returns MemoUpdateAction.UPDATE_CONTENT
            coEvery { validator.requireValidForUpdate("invalid") } throws validationError

            val thrown =
                runCatching {
                    useCase(memo, "invalid")
                }.exceptionOrNull()

            assertSame(validationError, thrown)
            coVerify(exactly = 0) { repository.updateMemo(any(), any()) }
            coVerify(exactly = 0) { deleteMemoUseCase.invoke(any()) }
        }
}
