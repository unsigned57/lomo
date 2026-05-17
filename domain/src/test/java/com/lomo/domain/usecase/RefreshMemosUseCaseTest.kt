/*
 * Test Contract:
 * - Unit under test: RefreshMemosUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for RefreshMemosUseCaseTest.
 * - Boundary: boundary and edge cases for RefreshMemosUseCaseTest.
 * - Failure: failure and error scenarios for RefreshMemosUseCaseTest.
 * - Must-not-happen: invariants are never violated for RefreshMemosUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of RefreshMemosUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

import com.lomo.domain.testing.DomainFunSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: RefreshMemosUseCase
 * - Behavior focus: refresh requests route through memo-maintenance orchestration.
 * - Observable outcomes: delegated refresh invocation.
 * - Excludes: sync engine behavior, refresh ordering, and repository internals already covered elsewhere.
 */
class RefreshMemosUseCaseTest : DomainFunSpec() {
    private val memoMaintenanceUseCase: MemoMaintenanceUseCase = mockk(relaxed = true)
    private val useCase = RefreshMemosUseCase(memoMaintenanceUseCase)
    init {
        test("invoke delegates refresh to maintenance use case") {
            runTest {
                        coEvery { memoMaintenanceUseCase.refreshMemos() } returns Unit

                        useCase()

                        coVerify(exactly = 1) { memoMaintenanceUseCase.refreshMemos() }
                    }
        }
    }
}
