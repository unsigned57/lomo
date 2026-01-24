
package com.lomo.data.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FormatDetectorTest {
    private lateinit var detector: FormatDetector

    @Before
    fun setup() {
        detector = FormatDetector()
    }

    @Test
    fun `detectFilenameFormat should identify yyyy_MM_dd`() {
        val filenames = listOf("2024_01_01.md", "2024_01_02.md", "other.md")
        val (detected, _) = detector.detectFormats(filenames, emptyList())
        assertEquals("yyyy_MM_dd", detected)
    }

    @Test
    fun `detectFilenameFormat should identify yyyy-MM-dd`() {
        val filenames = listOf("2024-01-01.md", "2024-01-02.md")
        val (detected, _) = detector.detectFormats(filenames, emptyList())
        assertEquals("yyyy-MM-dd", detected)
    }

    @Test
    fun `detectFilenameFormat should identify yyyy_MM_dd (dots)`() {
        val filenames = listOf("2024.01.01.md", "2024.01.02.md")
        val (detected, _) = detector.detectFormats(filenames, emptyList())
        assertEquals("yyyy.MM.dd", detected)
    }

    @Test
    fun `detectTimestampFormat should identify HH_mm_ss`() {
        val content = listOf("- 14:30:00 Task", "- 09:15:30 Task")
        val (_, detected) = detector.detectFormats(emptyList(), content)
        assertEquals("HH:mm:ss", detected)
    }

    @Test
    fun `detectTimestampFormat should identify hh_mm a`() {
        val content = listOf("- 02:30 PM Task", "- 10:15 AM Task")
        val (_, detected) = detector.detectFormats(emptyList(), content)
        assertEquals("hh:mm a", detected)
    }

    @Test
    fun `detectTimestampFormat should identify ISO format`() {
        val content = listOf("- 2024-01-01 14:30:00 Task", "- 2024-01-02 09:15:00 Task")
        val (_, detected) = detector.detectFormats(emptyList(), content)
        assertEquals("yyyy-MM-dd HH:mm:ss", detected)
    }
}
