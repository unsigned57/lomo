/*
 * Behavior Contract:
 * - Unit under test: DeleteMemoUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for DeleteMemoUseCaseTest.
 * - Boundary: boundary and edge cases for DeleteMemoUseCaseTest.
 * - Failure: failure and error scenarios for DeleteMemoUseCaseTest.
 * - Must-not-happen: invariants are never violated for DeleteMemoUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of DeleteMemoUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.domain.model.Memo
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: DeleteMemoUseCase
 * - Behavior focus: delete requests go through the shared memo-maintenance policy.
 * - Observable outcomes: delegated delete call with the selected memo.
 * - Excludes: repository deletion internals and trash/file synchronization behavior.
 */
class DeleteMemoUseCaseTest : DomainFunSpec() {
    private val repository = FakeMemoStore()
    private val useCase = DeleteMemoUseCase(com.lomo.domain.testing.fakes.FakeMemoMutationRepository(repository))
    init {
        test("invoke delegates delete to maintenance use case") {
            runTest {
                        val memo =
                            Memo(
                                id = "memo-1",
                                timestamp = 123L,
                                content = "delete me",
                                rawContent = "- 10:00 delete me",
                                dateKey = "2026_03_24",
                            )
                        useCase(memo)

                        repository.deletedMemoRequests shouldBe listOf(memo)
                    }
        }
    }
}
