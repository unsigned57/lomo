/*
 * Behavior Contract:
 * - Unit under test: LanSharePairingCodePolicy
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: parse and validate pairing codes for local area sharing.
 *
 * Scenarios:
 * - Given an input pairing code, when validating length, then return true only if trimmed length is between 6 and 64 characters.
 * - Given a message string, when checking invalid code message, then return true only if it matches standard invalid patterns.
 * - Given an exception from save operation, when extracting failure message, then map recognized exceptions or fallback to default.
 * - Given a message string, when resolving user message key, then map correctly to recognized enum keys.
 *
 * Observable outcomes:
 * - boolean validation result, mapped string message, and resolved UserMessageKey enum.
 *
 * TDD proof:
 * - Compilation failure on Kotest transition - test-only migration; no production change.
 *
 * Excludes:
 * - network pairing protocol, encryption algorithms, preference persistence.
 */

package com.lomo.app.feature.lanshare

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

        test("isInvalidPairingCodeMessage matches unified invalid messages") {
            ((LanSharePairingCodePolicy.isInvalidPairingCodeMessage("Pairing code must be 6-64 characters"))) shouldBe true
            ((LanSharePairingCodePolicy.isInvalidPairingCodeMessage("Invalid password"))) shouldBe true
            ((LanSharePairingCodePolicy.isInvalidPairingCodeMessage("Unknown error"))) shouldBe false
        }

        test("saveFailureMessage falls back to invalid length message") {
            val unknown = IllegalArgumentException("unexpected")
            val invalid = IllegalArgumentException("Invalid password")

            (LanSharePairingCodePolicy.saveFailureMessage(unknown)) shouldBe (LanSharePairingCodePolicy.INVALID_LENGTH_MESSAGE)
            (LanSharePairingCodePolicy.saveFailureMessage(invalid)) shouldBe (LanSharePairingCodePolicy.INVALID_PASSWORD_MESSAGE)
        }

        test("userMessageKey maps invalid and unknown message families") {
            (LanSharePairingCodePolicy.userMessageKey("Pairing code must be 6-64 characters")) shouldBe (LanSharePairingCodePolicy.UserMessageKey.INVALID_PAIRING_CODE)
            (LanSharePairingCodePolicy.userMessageKey("network down")) shouldBe (LanSharePairingCodePolicy.UserMessageKey.UNKNOWN)
        }
    }
}
