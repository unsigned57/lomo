package com.lomo.app.feature.lanshare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSharePairingDialogTriggerPolicyTest {
    @Test
    fun `pairing required event shows dialog only for positive token`() {
        assertTrue(LanSharePairingDialogTriggerPolicy.shouldShowOnPairingRequiredEvent(1))
        assertFalse(LanSharePairingDialogTriggerPolicy.shouldShowOnPairingRequiredEvent(0))
    }

    @Test
    fun `returns true only when e2e enabled and pairing not configured`() {
        assertTrue(
            LanSharePairingDialogTriggerPolicy.shouldShowOnE2eEnabled(
                enabled = true,
                pairingConfigured = false,
            ),
        )
        assertFalse(
            LanSharePairingDialogTriggerPolicy.shouldShowOnE2eEnabled(
                enabled = false,
                pairingConfigured = false,
            ),
        )
        assertFalse(
            LanSharePairingDialogTriggerPolicy.shouldShowOnE2eEnabled(
                enabled = true,
                pairingConfigured = true,
            ),
        )
    }
}
