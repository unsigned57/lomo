package com.lomo.data.parser


import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.usecase.MemoIdentityPolicy
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.KotestTemporaryFolder
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Test Contract:
 * - Unit under test: MarkdownParser
 * - Behavior focus: timestamped memo parsing, fallback timestamp/date resolution, and plain-Markdown file compatibility.
 * - Observable outcomes: parsed memo count, memo content, memo date/timestamp, and stable id shape.
 * - Red phase: Fails before the fix when a non-empty plain Markdown daily note parses as zero memos and disappears after sync refresh.
 * - Excludes: filesystem backend behavior, Room/index persistence, and sync transport orchestration.
 */
class MarkdownParserTest : DataFunSpec() {
    init {
        beforeTest {
            tempFolder = KotestTemporaryFolder()
            setup()
        }

        afterTest {
            tempFolder.cleanup()
        }

        test("test parse file with single memo and content on same line") { `test parse file with single memo and content on same line`() }

        test("test parse file with leading blank line") { `test parse file with leading blank line`() }

        test("test parse file with multi-line content") { `test parse file with multi-line content`() }

        test("test parse complex multi-paragraph format") { `test parse complex multi-paragraph format`() }

        test("test short time format HH_mm without seconds") { `test short time format HH_mm without seconds`() }

        test("test multiple memos in one file") { `test multiple memos in one file`() }

        test("test stable ids with hash") { `test stable ids with hash`() }

        test("test collision with identical content and timestamp") { `test collision with identical content and timestamp`() }

        test("test millisecond offsets for identical timestamps") { `test millisecond offsets for identical timestamps`() }

        test("test parse timestamp with dot filename format") { `test parse timestamp with dot filename format`() }

        test("test parse timestamp with month-first filename format") { `test parse timestamp with month-first filename format`() }

        test("test parse timestamp falls back to file metadata date when filename is unknown") { `test parse timestamp falls back to file metadata date when filename is unknown`() }

        test("test parse plain markdown file without timestamp headers as single memo") { `test parse plain markdown file without timestamp headers as single memo`() }
    }


    private lateinit var tempFolder: KotestTemporaryFolder
    private lateinit var parser: MarkdownParser

    fun setup() {
        parser = MarkdownParser(MemoTextProcessor(), MemoIdentityPolicy())
    }

    private fun `test parse file with single memo and content on same line`() {
        // Format: - HH:mm:ss Content
        val file = tempFolder.newFile("2026_01_10.md")
        file.writeText(
            """
- 22:02:46 Hello Lomo
            """.trimIndent(),
        )

        val memos = parser.parseFile(file)

        withClue("Should parse 1 memo") { memos.size shouldBe 1 }
        withClue("Content should be 'Hello Lomo'") { memos[0].content shouldBe "Hello Lomo" }
        // ID now includes content hash
        withClue("Timestamp should match prefix") { (memos[0].id.startsWith("2026_01_10_22:02:46_")).shouldBeTrue() }
    }

    private fun `test parse file with leading blank line`() {
        val file = tempFolder.newFile("2026_01_10_blank.md")
        file.writeText(
            """

- 22:02:46 Testing leading blank lines
            """.trimIndent(),
        )

        val memos = parser.parseFile(file)

        withClue("Should parse 1 memo even with leading blank") { memos.size shouldBe 1 }
        memos[0].content shouldBe "Testing leading blank lines"
    }

    private fun `test parse file with multi-line content`() {
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

        withClue("Should parse 1 memo") { memos.size shouldBe 1 }
        withClue("Content should contain first line") { (memos[0].content.contains("multi-line memo")).shouldBeTrue() }
        withClue("Content should contain subsequent lines") { (memos[0].content.contains("consistent with the original")).shouldBeTrue() }
    }

    private fun `test parse complex multi-paragraph format`() {
        val content =
            """- 21:57:35 
  First paragraph of the complex test case.
  
  Second paragraph with more details about the parser behavior. It should correctly capture the entire block until the next timestamp or EOF. 
  
  Third paragraph to ensure that multiple line breaks do not break the memo parsing logic prematurely."""

        val file = tempFolder.newFile("2022_05_02_complex.md")
        file.writeText(content)

        val memos = parser.parseFile(file)

        withClue("Should parse 1 memo") { memos.size shouldBe 1 }
        withClue("Should contain First paragraph") { (memos[0].content.contains("First paragraph")).shouldBeTrue() }
        withClue("Should contain Third paragraph") { (memos[0].content.contains("Third paragraph")).shouldBeTrue() }
    }

    private fun `test short time format HH_mm without seconds`() {
        val file = tempFolder.newFile("2026_01_11.md")
        file.writeText("- 10:30 Good morning")

        val memos = parser.parseFile(file)

        memos.size shouldBe 1
        memos[0].content shouldBe "Good morning"
    }

    private fun `test multiple memos in one file`() {
        val file = tempFolder.newFile("2026_01_12.md")
        file.writeText(
            """
- 08:00 Breakfast
- 12:00 Lunch
- 18:00 Dinner
            """.trimIndent(),
        )

        val memos = parser.parseFile(file)

        memos.size shouldBe 3
        memos[0].content shouldBe "Breakfast"
        // Verify ID contains date and timestamp. We can't predict hash easily in test without calc,
        // but we verify it's NOT just filename_time
        (memos[0].id.startsWith("2026_01_12_08:00_")).shouldBeTrue()

        memos[1].content shouldBe "Lunch"
        memos[2].content shouldBe "Dinner"
    }

    private fun `test stable ids with hash`() {
        // Scenario: Two notes with same timestamp.
        // File 1: Note A, Note B
        val content1 =
            """
- 10:00 Note A
- 10:00 Note B
            """.trimIndent()
        val memos1 = parser.parseContent(content1, "file1")
        val idAUsage1 = memos1[0].id
        val idBUsage1 = memos1[1].id

        // File 2: Note B only (Simulate deleting Note A)
        // If IDs were position based, Note B would take Note A's ID or change.
        // With hash, it should keep its own ID.
        val content2 =
            """
- 10:00 Note B
            """.trimIndent()
        val memos2 = parser.parseContent(content2, "file1")
        val idBUsage2 = memos2[0].id

        // Assert: ID of Note B should be identical in both cases
        withClue("ID of Note B should remain stable after deleting Note A") { idBUsage2 shouldBe idBUsage1 }
        // Also assert it's NOT the same as A's ID
        idAUsage1 shouldNotBe idBUsage2
    }

    private fun `test collision with identical content and timestamp`() {
        // Edge case: Identical content and timestamp
        val content =
            """
- 10:00 Duplicate
- 10:00 Duplicate
            """.trimIndent()

        val memos = parser.parseContent(content, "file1")
        memos.size shouldBe 2
        // First one has base hash ID
        // Second one should have suffix _1
        val baseId = memos[0].id
        val collisionId = memos[1].id

        withClue("Second ID should start with Base ID") { (collisionId.startsWith(baseId)).shouldBeTrue() }
        withClue("Second ID should have suffix") { (collisionId.endsWith("_1")).shouldBeTrue() }
    }

    private fun `test millisecond offsets for identical timestamps`() {
        val file = tempFolder.newFile("2026_01_13.md")
        file.writeText(
            """
- 10:00 Item 1
- 10:00 Item 2
- 10:00 Item 3
            """.trimIndent(),
        )

        val memos = parser.parseFile(file)

        memos.size shouldBe 3
        // Check that timestamps are sequential
        // Note: Actual timestamp value depends on parseTimestamp + offset
        // We just check that T2 > T1 and T3 > T2
        val t1 = memos[0].timestamp
        val t2 = memos[1].timestamp
        val t3 = memos[2].timestamp

        withClue("Item 2 should have later timestamp than Item 1") { (t2 > t1).shouldBeTrue() }
        withClue("Item 3 should have later timestamp than Item 2") { (t3 > t2).shouldBeTrue() }
        withClue("Difference should be 1ms") { t2 - t1 shouldBe 1 }
        withClue("Difference should be 1ms") { t3 - t2 shouldBe 1 }
    }

    private fun `test parse timestamp with dot filename format`() {
        val file = tempFolder.newFile("2024.01.31.md")
        file.writeText("- 10:30 Dot format")

        val memos = parser.parseFile(file)

        memos.size shouldBe 1
        val dateTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(memos[0].timestamp), ZoneId.systemDefault())
        dateTime.toLocalDate() shouldBe LocalDate.of(2024, 1, 31)
        dateTime.toLocalTime().withSecond(0).withNano(0) shouldBe LocalTime.of(10, 30)
    }

    private fun `test parse timestamp with month-first filename format`() {
        val file = tempFolder.newFile("01-31-2024.md")
        file.writeText("- 23:59 US date format")

        val memos = parser.parseFile(file)

        memos.size shouldBe 1
        val dateTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(memos[0].timestamp), ZoneId.systemDefault())
        dateTime.toLocalDate() shouldBe LocalDate.of(2024, 1, 31)
        dateTime.toLocalTime().withSecond(0).withNano(0) shouldBe LocalTime.of(23, 59)
    }

    private fun `test parse timestamp falls back to file metadata date when filename is unknown`() {
        val fallbackTime =
            LocalDateTime
                .of(2020, 2, 3, 14, 0, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

        val memos =
            parser.parseContent(
                content = "- 08:15 Unknown filename format",
                filename = "bad_filename",
                fallbackTimestampMillis = fallbackTime,
            )

        memos.size shouldBe 1
        val dateTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(memos[0].timestamp), ZoneId.systemDefault())
        dateTime.toLocalDate() shouldBe LocalDate.of(2020, 2, 3)
        dateTime.toLocalTime().withSecond(0).withNano(0) shouldBe LocalTime.of(8, 15)
    }

    private fun `test parse plain markdown file without timestamp headers as single memo`() {
        val fallbackTime =
            LocalDateTime
                .of(2026, 3, 25, 22, 48, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

        val memos =
            parser.parseContent(
                content =
                    """
                    # March 25

                    Wrote this on my computer.
                    It should still show up in Lomo after sync.
                    """.trimIndent(),
                filename = "2026_03_25",
                fallbackTimestampMillis = fallbackTime,
            )

        memos.size shouldBe 1
        memos.single().content shouldBe """
            # March 25

            Wrote this on my computer.
            It should still show up in Lomo after sync.
            """.trimIndent()
        memos.single().dateKey shouldBe "2026_03_25"
        val dateTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(memos.single().timestamp), ZoneId.systemDefault())
        dateTime.toLocalDate() shouldBe LocalDate.of(2026, 3, 25)
        dateTime.toLocalTime().withNano(0) shouldBe LocalTime.MIDNIGHT
        (memos.single().id.startsWith("2026_03_25_00:00:00_")).shouldBeTrue()
    }
}
