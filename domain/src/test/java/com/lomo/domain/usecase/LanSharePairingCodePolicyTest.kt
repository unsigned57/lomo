package com.lomo.domain.usecase

import io.kotest.assertions.fail
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: LanSharePairingCodePolicy
 * - Behavior focus: normalization, length validation, save-failure classification, and dialog-dismiss rules.
 * - Observable outcomes: normalized strings, Boolean validation results, thrown exception type, and user message keys/messages.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: persistence storage, UI dialog rendering, and transport authentication.
 */
class LanSharePairingCodePolicyTest : DomainFunSpec() {
    init {
        test("normalize trims input and validation honors inclusive length bounds") {
            LanSharePairingCodePolicy.normalize(" 123456 ") shouldBe "123456"
            (LanSharePairingCodePolicy.hasValidLength("123456")) shouldBe true
            (LanSharePairingCodePolicy.hasValidLength("x".repeat(LanSharePairingCodePolicy.MAX_LENGTH))) shouldBe true
            (LanSharePairingCodePolicy.hasValidLength("12345")) shouldBe false
            (LanSharePairingCodePolicy.hasValidLength("x".repeat(LanSharePairingCodePolicy.MAX_LENGTH + 1))) shouldBe false
        }
    }
    init {
        test("requireValid throws for invalid length and dialog dismissal mirrors validation") {
            try {
                LanSharePairingCodePolicy.requireValid("123")
                fail("Expected LanSharePairingCodeException")
            } catch (error: LanSharePairingCodeException) {
                error.message shouldBe LanSharePairingCodePolicy.INVALID_LENGTH_MESSAGE
            }

            (LanSharePairingCodePolicy.shouldDismissDialogAfterSave("654321")) shouldBe true
            (LanSharePairingCodePolicy.shouldDismissDialogAfterSave("bad")) shouldBe false
        }
    }
    init {
        test("saveFailureMessage and userMessageKey classify invalid-password variants") {
            LanSharePairingCodePolicy.saveFailureMessage(IllegalArgumentException("")) shouldBe LanSharePairingCodePolicy.INVALID_LENGTH_MESSAGE
            LanSharePairingCodePolicy.saveFailureMessage(
                    IllegalArgumentException(LanSharePairingCodePolicy.INVALID_PASSWORD_MESSAGE),
                ) shouldBe LanSharePairingCodePolicy.INVALID_PASSWORD_MESSAGE
            LanSharePairingCodePolicy.userMessageKey(" invalid password ") shouldBe LanSharePairingCodePolicy.UserMessageKey.INVALID_PAIRING_CODE
            LanSharePairingCodePolicy.userMessageKey(" Pairing code must be 6-64 characters ") shouldBe LanSharePairingCodePolicy.UserMessageKey.INVALID_PAIRING_CODE
            LanSharePairingCodePolicy.userMessageKey("server unreachable") shouldBe LanSharePairingCodePolicy.UserMessageKey.UNKNOWN
        }
    }
}
