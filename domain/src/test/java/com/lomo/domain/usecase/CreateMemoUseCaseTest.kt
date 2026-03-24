package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MemoRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: CreateMemoUseCase
 * - Behavior focus: workspace precondition enforcement before validation and save.
 * - Observable outcomes: thrown error message and repository save with validated input.
 * - Excludes: memo repository persistence internals and validator rule implementation.
 */
class CreateMemoUseCaseTest {
    private val memoRepository: MemoRepository = mockk(relaxed = true)
    private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase = mockk()
    private val validator: ValidateMemoContentUseCase = mockk(relaxed = true)
    private val useCase = CreateMemoUseCase(memoRepository, initializeWorkspaceUseCase, validator)

    @Test
    fun `invoke fails fast when workspace root is missing`() =
        runTest {
            coEvery { initializeWorkspaceUseCase.currentRootLocation() } returns null

            val error =
                runCatching {
                    useCase(content = "new memo", timestampMillis = 123L)
                }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertEquals("Please select a folder first", error?.message)
            coVerify(exactly = 0) { validator.requireValidForCreate(any()) }
            coVerify(exactly = 0) { memoRepository.saveMemo(any(), any()) }
        }

    @Test
    fun `invoke validates content then saves memo when workspace exists`() =
        runTest {
            coEvery { initializeWorkspaceUseCase.currentRootLocation() } returns StorageLocation("/workspace")

            useCase(content = "meaningful note", timestampMillis = 456L)

            coVerify(exactly = 1) { validator.requireValidForCreate("meaningful note") }
            coVerify(exactly = 1) { memoRepository.saveMemo("meaningful note", 456L) }
        }
}
