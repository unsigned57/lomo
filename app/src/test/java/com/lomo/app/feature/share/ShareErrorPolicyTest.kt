/*
 * Test Contract:
 * - Unit under test: ShareErrorPolicyTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for ShareErrorPolicyTest.
 * - Boundary: boundary and edge cases for ShareErrorPolicyTest.
 * - Failure: failure and error scenarios for ShareErrorPolicyTest.
 * - Must-not-happen: invariants are never violated for ShareErrorPolicyTest.
 *
 * - Behavior focus: test behavioral outcomes of ShareErrorPolicyTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.feature.share

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class ShareErrorPolicyTest : AppFunSpec() {
    private val policy = ShareErrorPolicy()

    init {
        test("sanitize falls back when message is technical") {
            val technical = "java.net.SocketTimeoutException: timeout\n\tat okhttp3.RealCall"

            val sanitized = policy.sanitizeUserFacingMessage(technical, fallbackMessage = "fallback")

            (sanitized) shouldBe ("fallback")
            ((policy.isTechnicalMessage(technical))) shouldBe true
        }
    }

    init {
        test("sanitize keeps user message") {
            val raw = "Transfer rejected by Pixel-9"

            val sanitized = policy.sanitizeUserFacingMessage(raw, fallbackMessage = "fallback")

            (sanitized) shouldBe (raw)
        }
    }

}
