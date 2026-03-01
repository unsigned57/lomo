package com.lomo.app.feature.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ThrowableMessageMapperTest {
    @Test
    fun `maps throwable message with optional prefix`() {
        val throwable = IllegalStateException("boom")

        assertEquals("boom", throwable.toUserMessage())
        assertEquals("Failed: boom", throwable.toUserMessage("Failed"))
    }

    @Test
    fun `falls back when throwable message is blank`() {
        val throwable = IllegalStateException("")

        assertEquals("Failed", throwable.toUserMessage("Failed"))
        assertEquals("Unexpected error", throwable.toUserMessage())
    }

    @Test
    fun `sanitizer path is preferred when provided`() {
        val throwable = IllegalStateException("socket timeout")

        val mapped =
            throwable.toUserMessage("Failed to sync") { raw, fallback ->
                if (raw?.contains("timeout", ignoreCase = true) == true) {
                    fallback
                } else {
                    raw.orEmpty()
                }
            }

        assertEquals("Failed to sync", mapped)
    }
}
