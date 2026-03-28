package com.lomo.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: LanSharePairingCodePolicy
 * - Behavior focus: normalization, length validation, save-failure classification, and dialog-dismiss rules.
 * - Observable outcomes: normalized strings, Boolean validation results, thrown exception type, and user message keys/messages.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: persistence storage, UI dialog rendering, and transport authentication.
 */
class LanSharePairingCodePolicyTest {
    @Test
    fun `normalize trims input and validation honors inclusive length bounds`() {
        assertEquals("123456", LanSharePairingCodePolicy.normalize(" 123456 "))
        assertTrue(LanSharePairingCodePolicy.hasValidLength("123456"))
        assertTrue(LanSharePairingCodePolicy.hasValidLength("x".repeat(LanSharePairingCodePolicy.MAX_LENGTH)))
        assertFalse(LanSharePairingCodePolicy.hasValidLength("12345"))
        assertFalse(LanSharePairingCodePolicy.hasValidLength("x".repeat(LanSharePairingCodePolicy.MAX_LENGTH + 1)))
    }

    @Test
    fun `requireValid throws for invalid length and dialog dismissal mirrors validation`() {
        try {
            LanSharePairingCodePolicy.requireValid("123")
            fail("Expected LanSharePairingCodeException")
        } catch (error: LanSharePairingCodeException) {
            assertEquals(LanSharePairingCodePolicy.INVALID_LENGTH_MESSAGE, error.message)
        }

        assertTrue(LanSharePairingCodePolicy.shouldDismissDialogAfterSave("654321"))
        assertFalse(LanSharePairingCodePolicy.shouldDismissDialogAfterSave("bad"))
    }

    @Test
    fun `saveFailureMessage and userMessageKey classify invalid-password variants`() {
        assertEquals(
            LanSharePairingCodePolicy.INVALID_LENGTH_MESSAGE,
            LanSharePairingCodePolicy.saveFailureMessage(IllegalArgumentException("")),
        )
        assertEquals(
            LanSharePairingCodePolicy.INVALID_PASSWORD_MESSAGE,
            LanSharePairingCodePolicy.saveFailureMessage(
                IllegalArgumentException(LanSharePairingCodePolicy.INVALID_PASSWORD_MESSAGE),
            ),
        )
        assertEquals(
            LanSharePairingCodePolicy.UserMessageKey.INVALID_PAIRING_CODE,
            LanSharePairingCodePolicy.userMessageKey(" invalid password "),
        )
        assertEquals(
            LanSharePairingCodePolicy.UserMessageKey.INVALID_PAIRING_CODE,
            LanSharePairingCodePolicy.userMessageKey(" Pairing code must be 6-64 characters "),
        )
        assertEquals(
            LanSharePairingCodePolicy.UserMessageKey.UNKNOWN,
            LanSharePairingCodePolicy.userMessageKey("server unreachable"),
        )
    }
}
