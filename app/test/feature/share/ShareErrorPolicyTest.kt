/*
 * Behavior Contract:
 * - Unit under test: ShareErrorPolicy
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: filter and sanitize error messages for sharing flow to protect users from technical details.
 *
 * Scenarios:
 * - Given a technical network exception message, when sanitizing, then map it to the fallback message and identify it as technical.
 * - Given a clean user-facing error message, when sanitizing, then preserve the raw message.
 *
 * Observable outcomes:
 * - sanitized error message string, and boolean indicating if message is technical.
 *
 * TDD proof:
 * - Compilation failure on Kotest transition - test-only migration; no production change.
 *
 * Excludes:
 * - file share protocol, device discovery.
 */

package com.lomo.app.feature.share

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

        test("sanitize keeps user message") {
            val raw = "Transfer rejected by Pixel-9"

            val sanitized = policy.sanitizeUserFacingMessage(raw, fallbackMessage = "fallback")

            (sanitized) shouldBe (raw)
        }
    }
}
