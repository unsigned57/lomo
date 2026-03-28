package com.lomo.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: StorageTimestampFormats
 * - Behavior focus: memo header parsing when invisible separators appear before the leading dash or between the dash and timestamp token.
 * - Observable outcomes: parsed header presence, parsed time token, and stripped content remainder.
 * - Red phase: Fails before the fix when a UTF-8 BOM or zero-width space prevents `- HH:mm:ss` memo headers from being recognized.
 * - Excludes: file loading, markdown rendering, and memo repository orchestration.
 */
class StorageTimestampFormatsInvisibleSeparatorTest {
    @Test
    fun `parseMemoHeaderLine ignores utf8 bom before storage header`() {
        val parsed = StorageTimestampFormats.parseMemoHeaderLine("﻿- 17:56:16  正文")

        assertNotNull(parsed)
        assertEquals("17:56:16", parsed?.timePart)
        assertEquals("正文", parsed?.contentPart)
    }

    @Test
    fun `parseMemoHeaderLine ignores zero width space between dash and timestamp`() {
        val parsed = StorageTimestampFormats.parseMemoHeaderLine("- ​17:56:16  正文")

        assertNotNull(parsed)
        assertEquals("17:56:16", parsed?.timePart)
        assertEquals("正文", parsed?.contentPart)
    }
}
