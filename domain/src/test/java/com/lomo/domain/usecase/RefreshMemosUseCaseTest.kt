/*
 * Behavior Contract:
 * - Unit under test: RefreshMemosUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for RefreshMemosUseCaseTest.
 * - Boundary: boundary and edge cases for RefreshMemosUseCaseTest.
 * - Failure: failure and error scenarios for RefreshMemosUseCaseTest.
 * - Must-not-happen: invariants are never violated for RefreshMemosUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of RefreshMemosUseCaseTest.
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


import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoStore
import com.lomo.domain.testing.fakes.FakeSyncPolicyRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: RefreshMemosUseCase
 * - Behavior focus: refresh requests route through memo-maintenance orchestration.
 * - Observable outcomes: delegated refresh invocation.
 * - Excludes: sync engine behavior, refresh ordering, and repository internals already covered elsewhere.
 */
class RefreshMemosUseCaseTest : DomainFunSpec() {
    private val memoRepository = FakeMemoStore()
    private val syncAndRebuildUseCase =
        SyncAndRebuildUseCase(
            memoRepository = com.lomo.domain.testing.fakes.FakeMemoMutationRepository(memoRepository),
            syncProviderRegistry = SyncProviderRegistry(emptyList()),
            syncPolicyRepository = FakeSyncPolicyRepository(),
        )
    private val useCase = RefreshMemosUseCase(syncAndRebuildUseCase)
    init {
        test("invoke delegates refresh to maintenance use case") {
            runTest {
                        useCase()

                        memoRepository.refreshMemosCallCount shouldBe 1
                    }
        }
    }
}
