/*
 * Behavior Contract:
 * - Unit under test: ResolveMemoUpdateActionUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for ResolveMemoUpdateActionUseCaseTest.
 * - Boundary: boundary and edge cases for ResolveMemoUpdateActionUseCaseTest.
 * - Failure: failure and error scenarios for ResolveMemoUpdateActionUseCaseTest.
 * - Must-not-happen: invariants are never violated for ResolveMemoUpdateActionUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of ResolveMemoUpdateActionUseCaseTest.
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
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: ResolveMemoUpdateActionUseCase
 * - Behavior focus: blank content maps to trashing while non-blank content stays on the update path.
 * - Observable outcomes: returned MemoUpdateAction for blank and non-blank editor content.
 * - Excludes: downstream deletion/update effects and editor UI behavior.
 */
class ResolveMemoUpdateActionUseCaseTest : DomainFunSpec() {
    private val useCase = ResolveMemoUpdateActionUseCase()
    init {
        test("invoke returns move-to-trash for blank input") {
            useCase("   ") shouldBe MemoUpdateAction.MOVE_TO_TRASH
        }

        test("invoke returns update-content for non-blank input") {
            useCase("keep this memo") shouldBe MemoUpdateAction.UPDATE_CONTENT
        }
    }
}
