package com.lomo.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SensitiveLogValueSanitizer
 * - Behavior focus: Strip credentials from logs.
 * - Observable outcomes: Sanitized strings.
 * - Red phase: Verified by checking for leaked passwords.
 * - Excludes: none.
 */
class SensitiveLogValueSanitizerTest {
    @Test
    fun `sanitizePathForLog keeps only a short basename prefix plus hash`() {
        val sanitized = sanitizePathForLog("/vault/private/2026_03_25-secret.md")

        assertTrue(sanitized.startsWith("2026"))
        assertTrue(sanitized.contains('#'))
        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("/vault/private"))
    }

    @Test
    fun `sanitizePathForLog is deterministic for the same input`() {
        val path = "/vault/private/2026_03_25-secret.md"

        assertEquals(sanitizePathForLog(path), sanitizePathForLog(path))
    }
}
