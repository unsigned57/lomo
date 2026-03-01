package com.lomo.app.feature.lanshare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LanSharePairingCodePolicyTest {
    @Test
    fun `hasValidLength checks trimmed code length range`() {
        assertFalse(LanSharePairingCodePolicy.hasValidLength("12345"))
        assertTrue(LanSharePairingCodePolicy.hasValidLength("123456"))
        assertTrue(LanSharePairingCodePolicy.hasValidLength(" ${"a".repeat(64)} "))
        assertFalse(LanSharePairingCodePolicy.hasValidLength("${"a".repeat(65)}"))
    }

    @Test
    fun `isInvalidPairingCodeMessage matches unified invalid messages`() {
        assertTrue(LanSharePairingCodePolicy.isInvalidPairingCodeMessage("Pairing code must be 6-64 characters"))
        assertTrue(LanSharePairingCodePolicy.isInvalidPairingCodeMessage("Invalid password"))
        assertFalse(LanSharePairingCodePolicy.isInvalidPairingCodeMessage("Unknown error"))
    }

    @Test
    fun `saveFailureMessage falls back to invalid length message`() {
        val unknown = IllegalArgumentException("unexpected")
        val invalid = IllegalArgumentException("Invalid password")

        assertEquals(
            LanSharePairingCodePolicy.INVALID_LENGTH_MESSAGE,
            LanSharePairingCodePolicy.saveFailureMessage(unknown),
        )
        assertEquals(
            LanSharePairingCodePolicy.INVALID_PASSWORD_MESSAGE,
            LanSharePairingCodePolicy.saveFailureMessage(invalid),
        )
    }

    @Test
    fun `userMessageKey maps invalid and unknown message families`() {
        assertEquals(
            LanSharePairingCodePolicy.UserMessageKey.INVALID_PAIRING_CODE,
            LanSharePairingCodePolicy.userMessageKey("Pairing code must be 6-64 characters"),
        )
        assertEquals(
            LanSharePairingCodePolicy.UserMessageKey.UNKNOWN,
            LanSharePairingCodePolicy.userMessageKey("network down"),
        )
    }
}
