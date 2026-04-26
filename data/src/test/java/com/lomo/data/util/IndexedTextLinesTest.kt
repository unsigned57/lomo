package com.lomo.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: IndexedTextLines
 * - Behavior focus: Index text lines for FTS insertion.
 * - Observable outcomes: Correct line offsets.
 * - Red phase: Verified by asserting bad offsets.
 * - Excludes: none.
 */
class IndexedTextLinesTest {
    @Test
    fun `indexed text lines mirrors kotlin lines across newline styles`() {
        val content = "first\r\nsecond\nthird\rfourth\n"

        assertEquals(content.lines(), IndexedTextLines.of(content))
    }

    @Test
    fun `indexed text lines preserves empty input contract`() {
        assertEquals(listOf(""), IndexedTextLines.of(""))
    }

    @Test
    fun `find destructive memo block works with indexed text lines`() {
        val content =
            """
            - 09:00 keep
            still keep
            - 10:00 target
            target body
            - 11:00 stay
            """.trimIndent()

        assertEquals(
            2 to 3,
            findDestructiveMemoBlock(
                lines = IndexedTextLines.of(content),
                rawContent = "- 10:00 target\ntarget body",
                memoId = null,
            ),
        )
    }
}
