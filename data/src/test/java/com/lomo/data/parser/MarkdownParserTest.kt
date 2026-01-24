package com.lomo.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarkdownParserTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var parser: MarkdownParser

    @Before
    fun setup() {
        parser = MarkdownParser()
    }

    @Test
    fun `test parse file with single memo and content on same line`() {
        // Format: - HH:mm:ss Content
        val file = tempFolder.newFile("2026_01_10.md")
        file.writeText(
            """
- 22:02:46 Hello Lomo
            """.trimIndent(),
        )

        val memos = parser.parseFile(file)

        assertEquals("Should parse 1 memo", 1, memos.size)
        assertEquals("Content should be 'Hello Lomo'", "Hello Lomo", memos[0].content)
        assertEquals("Timestamp should match", "2026_01_10_22:02:46", memos[0].id)
    }

    @Test
    fun `test parse file with leading blank line`() {
        val file = tempFolder.newFile("2026_01_10_blank.md")
        file.writeText(
            """

- 22:02:46 Testing leading blank lines
            """.trimIndent(),
        )

        val memos = parser.parseFile(file)

        assertEquals("Should parse 1 memo even with leading blank", 1, memos.size)
        assertEquals("Testing leading blank lines", memos[0].content)
    }

    @Test
    fun `test parse file with multi-line content`() {
        val file = tempFolder.newFile("2022_05_02.md")
        file.writeText(
            """
- 21:57:35 
  This is a multi-line memo.
  
  It should support empty lines and indentation
  consistent with the original format.
            """.trimIndent(),
        )

        val memos = parser.parseFile(file)

        assertEquals("Should parse 1 memo", 1, memos.size)
        assertTrue("Content should contain first line", memos[0].content.contains("multi-line memo"))
        assertTrue("Content should contain subsequent lines", memos[0].content.contains("consistent with the original"))
    }

    @Test
    fun `test parse complex multi-paragraph format`() {
        val content =
            """- 21:57:35 
  First paragraph of the complex test case.
  
  Second paragraph with more details about the parser behavior. It should correctly capture the entire block until the next timestamp or EOF. 
  
  Third paragraph to ensure that multiple line breaks do not break the memo parsing logic prematurely."""

        val file = tempFolder.newFile("2022_05_02_complex.md")
        file.writeText(content)

        val memos = parser.parseFile(file)

        assertEquals("Should parse 1 memo", 1, memos.size)
        assertTrue("Should contain First paragraph", memos[0].content.contains("First paragraph"))
        assertTrue("Should contain Third paragraph", memos[0].content.contains("Third paragraph"))
    }

    @Test
    fun `test short time format HH_mm without seconds`() {
        val file = tempFolder.newFile("2026_01_11.md")
        file.writeText("- 10:30 Good morning")

        val memos = parser.parseFile(file)

        assertEquals(1, memos.size)
        assertEquals("Good morning", memos[0].content)
    }

    @Test
    fun `test multiple memos in one file`() {
        val file = tempFolder.newFile("2026_01_12.md")
        file.writeText(
            """
- 08:00 Breakfast
- 12:00 Lunch
- 18:00 Dinner
            """.trimIndent(),
        )

        val memos = parser.parseFile(file)

        assertEquals(3, memos.size)
        assertEquals("Breakfast", memos[0].content)
        assertEquals("Lunch", memos[1].content)
        assertEquals("Dinner", memos[2].content)
    }
}
