/*
 * Test Contract:
 * - Unit under test: CreateMemoUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for CreateMemoUseCaseTest.
 * - Boundary: boundary and edge cases for CreateMemoUseCaseTest.
 * - Failure: failure and error scenarios for CreateMemoUseCaseTest.
 * - Must-not-happen: invariants are never violated for CreateMemoUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of CreateMemoUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: CreateMemoUseCase
 * - Behavior focus: workspace precondition enforcement before validation and save.
 * - Observable outcomes: thrown error message and repository save with validated input.
 * - Excludes: memo repository persistence internals and validator rule implementation.
 */
class CreateMemoUseCaseTest : DomainFunSpec() {
    private val memoRepository: MemoRepository = mockk(relaxed = true)
    private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase = mockk()
    private val validator: ValidateMemoContentUseCase = mockk(relaxed = true)
    private val useCase = CreateMemoUseCase(memoRepository, initializeWorkspaceUseCase, validator)
    init {
        test("invoke fails fast when workspace root is missing") {
            runTest {
                        coEvery { initializeWorkspaceUseCase.currentRootLocation() } returns null

                        val error =
                            runCatching {
                                useCase(content = "new memo", timestampMillis = 123L)
                            }.exceptionOrNull()

                        val missingWorkspace = error.shouldBeInstanceOf<IllegalStateException>()
                        missingWorkspace.message shouldBe "Please select a folder first"
                        coVerify(exactly = 0) { validator.requireValidForCreate(any()) }
                        coVerify(exactly = 0) { memoRepository.saveMemo(any(), any()) }
                    }
        }
    }
    init {
        test("invoke validates content then saves memo when workspace exists") {
            runTest {
                        coEvery { initializeWorkspaceUseCase.currentRootLocation() } returns StorageLocation("/workspace")

                        useCase(content = "meaningful note", timestampMillis = 456L)

                        coVerify(exactly = 1) { validator.requireValidForCreate("meaningful note") }
                        coVerify(exactly = 1) { memoRepository.saveMemo("meaningful note", 456L) }
                    }
        }
    }
}
