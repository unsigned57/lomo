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
        // ID now includes content hash
        assertTrue("Timestamp should match prefix", memos[0].id.startsWith("2026_01_10_22:02:46_"))
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
        // Verify ID contains date and timestamp. We can't predict hash easily in test without calc,
        // but we verify it's NOT just filename_time
        assertTrue(memos[0].id.startsWith("2026_01_12_08:00_"))

        assertEquals("Lunch", memos[1].content)
        assertEquals("Dinner", memos[2].content)
    }

    @Test
    fun `test stable ids with hash`() {
        // Scenario: Two notes with same timestamp.
        // File 1: Note A, Note B
        val content1 =
            """
- 10:00 Note A
- 10:00 Note B
            """.trimIndent()
        val memos1 = parser.parseContent(content1, "file1")
        val idA_usage1 = memos1[0].id
        val idB_usage1 = memos1[1].id

        // File 2: Note B only (Simulate deleting Note A)
        // If IDs were position based, Note B would take Note A's ID or change.
        // With hash, it should keep its own ID.
        val content2 =
            """
- 10:00 Note B
            """.trimIndent()
        val memos2 = parser.parseContent(content2, "file1")
        val idB_usage2 = memos2[0].id

        // Assert: ID of Note B should be identical in both cases
        assertEquals("ID of Note B should remain stable after deleting Note A", idB_usage1, idB_usage2)
        // Also assert it's NOT the same as A's ID
        org.junit.Assert.assertNotEquals(idA_usage1, idB_usage2)
    }

    @Test
    fun `test collision with identical content and timestamp`() {
        // Edge case: Identical content and timestamp
        val content =
            """
- 10:00 Duplicate
- 10:00 Duplicate
            """.trimIndent()

        val memos = parser.parseContent(content, "file1")
        assertEquals(2, memos.size)
        // First one has base hash ID
        // Second one should have suffix _1
        val baseId = memos[0].id
        val collisionId = memos[1].id

        assertTrue("Second ID should start with Base ID", collisionId.startsWith(baseId))
        assertTrue("Second ID should have suffix", collisionId.endsWith("_1"))
    }

    @Test
    fun `test millisecond offsets for identical timestamps`() {
        val file = tempFolder.newFile("2026_01_13.md")
        file.writeText(
            """
- 10:00 Item 1
- 10:00 Item 2
- 10:00 Item 3
            """.trimIndent(),
        )

        val memos = parser.parseFile(file)

        assertEquals(3, memos.size)
        // Check that timestamps are sequential
        // Note: Actual timestamp value depends on parseTimestamp + offset
        // We just check that T2 > T1 and T3 > T2
        val t1 = memos[0].timestamp
        val t2 = memos[1].timestamp
        val t3 = memos[2].timestamp

        assertTrue("Item 2 should have later timestamp than Item 1", t2 > t1)
        assertTrue("Item 3 should have later timestamp than Item 2", t3 > t2)
        assertEquals("Difference should be 1ms", 1, t2 - t1)
        assertEquals("Difference should be 1ms", 1, t3 - t2)
    }
}
