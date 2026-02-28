package com.lomo.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StorageTimestampFormatsTest {
    @Test
    fun `parseMemoHeaderLine returns null for malformed or non-header input`() {
        assertNull(StorageTimestampFormats.parseMemoHeaderLine(""))
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("   "))
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("09:30 content"))
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("-"))
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("-    "))
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("- content only"))
    }

    @Test
    fun `parseMemoHeaderLine returns null when timestamp fields are missing`() {
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("- 09 content"))
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("- :30 content"))
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("- 09: content"))
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("- 09::30 content"))
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("- 09:30: content"))
    }

    @Test
    fun `parseMemoHeaderLine returns null for illegal time values`() {
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("- 24:01 overflow hour"))
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("- 09:60 overflow minute"))
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("- 09:10:60 overflow second"))
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("- 99:00 clearly invalid"))
    }

    @Test
    fun `parseMemoHeaderLine rejects non-whitespace token boundaries`() {
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("- 09:30content"))
        assertNull(StorageTimestampFormats.parseMemoHeaderLine("- 9:30:05content"))
    }

    @Test
    fun `parseMemoHeaderLine parses supported time formats and content`() {
        val withMinutes = StorageTimestampFormats.parseMemoHeaderLine("- 09:30 hello")
        assertNotNull(withMinutes)
        assertEquals("09:30", withMinutes?.timePart)
        assertEquals("hello", withMinutes?.contentPart)

        val singleDigitHour = StorageTimestampFormats.parseMemoHeaderLine("  - 9:30 hi")
        assertNotNull(singleDigitHour)
        assertEquals("9:30", singleDigitHour?.timePart)
        assertEquals("hi", singleDigitHour?.contentPart)

        val withSeconds = StorageTimestampFormats.parseMemoHeaderLine("- 9:30:05 details")
        assertNotNull(withSeconds)
        assertEquals("9:30:05", withSeconds?.timePart)
        assertEquals("details", withSeconds?.contentPart)

        val noContent = StorageTimestampFormats.parseMemoHeaderLine("- 09:30")
        assertNotNull(noContent)
        assertEquals("09:30", noContent?.timePart)
        assertEquals("", noContent?.contentPart)
    }
}
