package com.lomo.data.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MemoTextProcessorTest {
    private lateinit var processor: MemoTextProcessor
    private val timestamp = 1705234800000L // Example timestamp

    @Before
    fun setup() {
        processor = MemoTextProcessor()
    }

    @Test
    fun `findMemoBlock should identify block with HH_mm_ss format`() {
        val lines =
            listOf(
                "- 09:40:00 Old Content",
                "",
                "- 12:00:00 Another block",
            )
        val rawContent = "- 09:40:00 Old Content"
        // Adjust timestamp to match 09:40:00 roughly if needed or mock logic
        // But the fallback uses current system zone.
        // Let's rely on exact string match first.

        val (start, end) = processor.findMemoBlock(lines, rawContent, timestamp)
        // Expect index 0 to 1 (empty line usually end or next block start?)
        // Regex MEMO_BLOCK_END matches next block start "- 12:00:00"

        assertEquals(0, start)
        // The loop breaks at line 2 ("- 12:00:00"), so end should be 1.
        assertEquals(1, end)
    }

    @Test
    fun `findMemoBlock should identify block with HH_mm format`() {
        // Test permissive regex
        val lines =
            listOf(
                "- 09:40 Short format",
                "Details here",
                "- 12:00 Next block",
            )
        val rawContent = "- 09:40 Short format"

        val (start, end) = processor.findMemoBlock(lines, rawContent, timestamp)

        assertEquals(0, start)
        assertEquals(1, end)
    }

    @Test
    fun `replaceMemoBlock should use provided timestampStr`() {
        val lines = mutableListOf("- 09:00:00 Old")
        val result =
            processor.replaceMemoBlock(
                lines,
                "- 09:00:00 Old",
                timestamp,
                "New Content",
                "10:00", // Custom short format
            )

        assertTrue(result)
        assertEquals("- 10:00 New Content", lines[0])
    }
}
