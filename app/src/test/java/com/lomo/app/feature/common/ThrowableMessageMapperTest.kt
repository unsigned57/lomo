/*
 * Test Contract:
 * - Unit under test: ThrowableMessageMapperTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for ThrowableMessageMapperTest.
 * - Boundary: boundary and edge cases for ThrowableMessageMapperTest.
 * - Failure: failure and error scenarios for ThrowableMessageMapperTest.
 * - Must-not-happen: invariants are never violated for ThrowableMessageMapperTest.
 *
 * - Behavior focus: test behavioral outcomes of ThrowableMessageMapperTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.feature.common

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class ThrowableMessageMapperTest : AppFunSpec() {
    init {
        test("maps throwable message with optional prefix") {
            val throwable = IllegalStateException("boom")

            (throwable.toUserMessage()) shouldBe ("boom")
            (throwable.toUserMessage("Failed")) shouldBe ("Failed: boom")
        }
    }

    init {
        test("falls back when throwable message is blank") {
            val throwable = IllegalStateException("")

            (throwable.toUserMessage("Failed")) shouldBe ("Failed")
            (throwable.toUserMessage()) shouldBe ("Unexpected error")
        }
    }

    init {
        test("sanitizer path is preferred when provided") {
            val throwable = IllegalStateException("socket timeout")

            val mapped =
                throwable.toUserMessage("Failed to sync") { raw, fallback ->
                    if (raw?.contains("timeout", ignoreCase = true) == true) {
                        fallback
                    } else {
                        raw.orEmpty()
                    }
                }

            (mapped) shouldBe ("Failed to sync")
        }
    }

}
