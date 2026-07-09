/*
 * Behavior Contract:
 * - Unit under test: GitSyncErrorUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for GitSyncErrorUseCaseTest.
 * - Boundary: boundary and edge cases for GitSyncErrorUseCaseTest.
 * - Failure: failure and error scenarios for GitSyncErrorUseCaseTest.
 * - Must-not-happen: invariants are never violated for GitSyncErrorUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of GitSyncErrorUseCaseTest.
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

class GitSyncErrorUseCaseTest : DomainFunSpec() {
    private val policy = GitSyncErrorUseCase()
    init {
        test("sanitize keeps conflict message") {
            val raw = "rebase STOPPED: resolve conflicts manually"

            val sanitized = policy.sanitizeUserFacingMessage(raw, fallbackMessage = "fallback")

            sanitized shouldBe raw
            (policy.isConflictMessage(raw)) shouldBe true
        }

        test("sanitize keeps direct-path-required message") {
            val raw = "Git sync requires direct path mode to run"

            val sanitized = policy.sanitizeUserFacingMessage(raw, fallbackMessage = "fallback")

            sanitized shouldBe raw
            policy.classify(raw) shouldBe GitSyncErrorUseCase.ErrorKind.DIRECT_PATH_REQUIRED
        }

        test("sanitize falls back for technical details") {
            val raw = "java.net.SocketTimeoutException: timeout\n\tat okhttp3.RealCall.execute"

            val sanitized = policy.sanitizeUserFacingMessage(raw, fallbackMessage = "fallback")

            sanitized shouldBe "fallback"
            (policy.looksTechnicalMessage(raw)) shouldBe true
            policy.classify(raw) shouldBe GitSyncErrorUseCase.ErrorKind.TECHNICAL
        }

        test("classify returns user-facing for simple message") {
            val raw = "Push rejected: remote ref was updated during push."

            policy.classify(raw) shouldBe GitSyncErrorUseCase.ErrorKind.USER_FACING
        }
    }
}
