/*
 * Test Contract:
 * - Unit under test: LanSharePairingCodePolicyTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for LanSharePairingCodePolicyTest.
 * - Boundary: boundary and edge cases for LanSharePairingCodePolicyTest.
 * - Failure: failure and error scenarios for LanSharePairingCodePolicyTest.
 * - Must-not-happen: invariants are never violated for LanSharePairingCodePolicyTest.
 *
 * - Behavior focus: test behavioral outcomes of LanSharePairingCodePolicyTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.feature.lanshare

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.usecase.LanSharePairingCodePolicy
import io.kotest.matchers.shouldBe

class LanSharePairingCodePolicyTest : AppFunSpec() {
    init {
        test("hasValidLength checks trimmed code length range") {
            ((LanSharePairingCodePolicy.hasValidLength("12345"))) shouldBe false
            ((LanSharePairingCodePolicy.hasValidLength("123456"))) shouldBe true
            ((LanSharePairingCodePolicy.hasValidLength(" ${"a".repeat(64)} "))) shouldBe true
            ((LanSharePairingCodePolicy.hasValidLength("${"a".repeat(65)}"))) shouldBe false
        }
    }

    init {
        test("isInvalidPairingCodeMessage matches unified invalid messages") {
            ((LanSharePairingCodePolicy.isInvalidPairingCodeMessage("Pairing code must be 6-64 characters"))) shouldBe true
            ((LanSharePairingCodePolicy.isInvalidPairingCodeMessage("Invalid password"))) shouldBe true
            ((LanSharePairingCodePolicy.isInvalidPairingCodeMessage("Unknown error"))) shouldBe false
        }
    }

    init {
        test("saveFailureMessage falls back to invalid length message") {
            val unknown = IllegalArgumentException("unexpected")
            val invalid = IllegalArgumentException("Invalid password")

            (LanSharePairingCodePolicy.saveFailureMessage(unknown)) shouldBe (LanSharePairingCodePolicy.INVALID_LENGTH_MESSAGE)
            (LanSharePairingCodePolicy.saveFailureMessage(invalid)) shouldBe (LanSharePairingCodePolicy.INVALID_PASSWORD_MESSAGE)
        }
    }

    init {
        test("userMessageKey maps invalid and unknown message families") {
            (LanSharePairingCodePolicy.userMessageKey("Pairing code must be 6-64 characters")) shouldBe (LanSharePairingCodePolicy.UserMessageKey.INVALID_PAIRING_CODE)
            (LanSharePairingCodePolicy.userMessageKey("network down")) shouldBe (LanSharePairingCodePolicy.UserMessageKey.UNKNOWN)
        }
    }

}
