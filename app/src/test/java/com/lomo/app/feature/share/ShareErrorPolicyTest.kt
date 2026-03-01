package com.lomo.app.feature.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareErrorPolicyTest {
    private val policy = ShareErrorPolicy()

    @Test
    fun `sanitize falls back when message is technical`() {
        val technical = "java.net.SocketTimeoutException: timeout\n\tat okhttp3.RealCall"

        val sanitized = policy.sanitizeUserFacingMessage(technical, fallbackMessage = "fallback")

        assertEquals("fallback", sanitized)
        assertTrue(policy.isTechnicalMessage(technical))
    }

    @Test
    fun `sanitize keeps user message`() {
        val raw = "Transfer rejected by Pixel-9"

        val sanitized = policy.sanitizeUserFacingMessage(raw, fallbackMessage = "fallback")

        assertEquals(raw, sanitized)
    }
}
