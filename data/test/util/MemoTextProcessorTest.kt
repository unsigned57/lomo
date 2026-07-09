package com.lomo.data.util

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.data.memo.MemoContentHashPolicy
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Behavior Contract:
 * - Unit under test: MemoTextProcessor
 * - Behavior focus: memo block replacement and lookup, checkbox toggling, and attachment or tag extraction.
 * - Observable outcomes: returned block coordinates, rewritten text content, boolean success flags, and extracted lists.
 * - TDD proof: Fails before the fix when replacing a memo with empty content leaves a trailing space after the timestamp header.
 * - Excludes: markdown parser internals, file-system behavior, and UI rendering.
 */
class MemoTextProcessorTest : DataFunSpec() {
    init {
        beforeTest {
            setup()
        }

        test("findMemoBlock should identify block with HH_mm_ss format") { `findMemoBlock should identify block with HH_mm_ss format`() }

        test("findMemoBlock should identify block with HH_mm format") { `findMemoBlock should identify block with HH_mm format`() }

        test("replaceMemoBlock should use provided timestampStr") { `replaceMemoBlock should use provided timestampStr`() }

        test("replaceMemoBlock should keep timestamp only when new content is empty") { `replaceMemoBlock should keep timestamp only when new content is empty`() }

        test("replaceMemoBlock should return false when target block is missing") { `replaceMemoBlock should return false when target block is missing`() }

        test("findMemoBlock should locate collision entry by memoId") { `findMemoBlock should locate collision entry by memoId`() }

        test("removeMemoBlock should remove collision entry by memoId") { `removeMemoBlock should remove collision entry by memoId`() }

        test("findMemoBlock should fall back to raw content when memoId is invalid") { `findMemoBlock should fall back to raw content when memoId is invalid`() }

        test("toggleCheckbox should replace unchecked item when marking complete") { `toggleCheckbox should replace unchecked item when marking complete`() }

        test("toggleCheckbox should return original content when line index is out of bounds") { `toggleCheckbox should return original content when line index is out of bounds`() }

        test("toggleCheckbox should return original content when expected pattern is missing") { `toggleCheckbox should return original content when expected pattern is missing`() }

        test("extractTags should trim trailing slash and deduplicate tags") { `extractTags should trim trailing slash and deduplicate tags`() }

        test("extractTags should keep emoji tags intact") { `extractTags should keep emoji tags intact`() }

        test("extractTags should stop at commas instead of merging adjacent text") { `extractTags should stop at commas instead of merging adjacent text`() }

        test("extractAudioLinks should include markdown audio links") { `extractAudioLinks should include markdown audio links`() }

        test("extractLocalAttachmentPaths should merge image and audio and skip remote urls") { `extractLocalAttachmentPaths should merge image and audio and skip remote urls`() }
    }


    private lateinit var processor: MemoTextProcessor
    private val timestamp = 1705234800000L // Example timestamp

    private fun setup() {
        processor = MemoTextProcessor()
    }

    private fun `findMemoBlock should identify block with HH_mm_ss format`() {
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

        start shouldBe 0
        // The loop breaks at line 2 ("- 12:00:00"), so end should be 1.
        end shouldBe 1
    }

    private fun `findMemoBlock should identify block with HH_mm format`() {
        // Test permissive regex
        val lines =
            listOf(
                "- 09:40 Short format",
                "Details here",
                "- 12:00 Next block",
            )
        val rawContent = "- 09:40 Short format"

        val (start, end) = processor.findMemoBlock(lines, rawContent, timestamp)

        start shouldBe 0
        end shouldBe 1
    }

    private fun `replaceMemoBlock should use provided timestampStr`() {
        val lines = mutableListOf("- 09:00:00 Old")
        val result =
            processor.replaceMemoBlock(
                lines,
                "- 09:00:00 Old",
                timestamp,
                "New Content",
                "10:00", // Custom short format
            )

        (result).shouldBeTrue()
        lines[0] shouldBe "- 10:00 New Content"
    }

    private fun `replaceMemoBlock should keep timestamp only when new content is empty`() {
        val lines =
            mutableListOf(
                "- 09:00:00 Old",
                "details",
            )

        val replaced =
            processor.replaceMemoBlock(
                lines = lines,
                rawContent = "- 09:00:00 Old\ndetails",
                timestamp = timestamp,
                newRawContent = "",
                timestampStr = "10:00",
            )

        (replaced).shouldBeTrue()
        lines shouldBe listOf("- 10:00")
    }

    private fun `replaceMemoBlock should return false when target block is missing`() {
        val lines = mutableListOf("- 09:00 Existing")

        val replaced =
            processor.replaceMemoBlock(
                lines = lines,
                rawContent = "- 10:00 Missing",
                timestamp = timestamp,
                newRawContent = "New",
                timestampStr = "10:30",
            )

        (replaced).shouldBeFalse()
        lines shouldBe listOf("- 09:00 Existing")
    }

    private fun `findMemoBlock should locate collision entry by memoId`() {
        val lines =
            listOf(
                "- 12:30 Duplicate",
                "- 12:30 Duplicate",
            )
        val contentHash = MemoContentHashPolicy.hashHex("Duplicate")
        val baseId = "2024_01_15_12:30_$contentHash"
        val secondId = "${baseId}_1"

        val (start, end) =
            processor.findMemoBlock(
                lines = lines,
                rawContent = "- 12:30 Duplicate",
                timestamp = timestamp,
                memoId = secondId,
            )

        start shouldBe 1
        end shouldBe 1
    }

    private fun `removeMemoBlock should remove collision entry by memoId`() {
        val lines =
            mutableListOf(
                "- 12:30 Duplicate",
                "- 12:30 Duplicate",
            )
        val contentHash = MemoContentHashPolicy.hashHex("Duplicate")
        val baseId = "2024_01_15_12:30_$contentHash"
        val secondId = "${baseId}_1"

        val removed =
            processor.removeMemoBlock(
                lines = lines,
                rawContent = "- 12:30 Duplicate",
                timestamp = timestamp,
                memoId = secondId,
            )

        (removed).shouldBeTrue()
        lines.size shouldBe 1
    }

    private fun `findMemoBlock should fall back to raw content when memoId is invalid`() {
        val lines =
            listOf(
                "- 09:40 First line",
                "Second line",
                "- 12:00 Another block",
            )

        val (start, end) =
            processor.findMemoBlock(
                lines = lines,
                rawContent = "- 09:40 First line\nSecond line",
                timestamp = timestamp,
                memoId = "not_a_valid_id",
            )

        start shouldBe 0
        end shouldBe 1
    }

    private fun `toggleCheckbox should replace unchecked item when marking complete`() {
        val content =
            """
            title
            - [ ] buy milk
            """.trimIndent()

        val toggled = processor.toggleCheckbox(content = content, lineIndex = 1, checked = true)

        toggled shouldBe """
            title
            - [x] buy milk
            """.trimIndent()
    }

    private fun `toggleCheckbox should return original content when line index is out of bounds`() {
        val content = "- [ ] buy milk"

        val toggled = processor.toggleCheckbox(content = content, lineIndex = 3, checked = true)

        toggled shouldBe content
    }

    private fun `toggleCheckbox should return original content when expected pattern is missing`() {
        val content =
            """
            title
            - note only
            """.trimIndent()

        val toggled = processor.toggleCheckbox(content = content, lineIndex = 1, checked = true)

        toggled shouldBe content
    }

    private fun `extractTags should trim trailing slash and deduplicate tags`() {
        val content = "#travel/ revisit #travel #苏格拉底/ #苏格拉底"

        val tags = processor.extractTags(content)

        tags shouldBe listOf("travel", "苏格拉底")
    }

    private fun `extractTags should keep emoji tags intact`() {
        val content = "#😀工作 update #🎉/ #😀工作"

        val tags = processor.extractTags(content)

        tags shouldBe listOf("😀工作", "🎉")
    }

    private fun `extractTags should stop at commas instead of merging adjacent text`() {
        val content = "#work,next #travel,plan"

        val tags = processor.extractTags(content)

        tags shouldBe listOf("work", "travel")
    }

    private fun `extractAudioLinks should include markdown audio links`() {
        val content = "[audio](voice_001.m4a)\n[text](doc.txt)\n[clip](music.MP3)"

        val links = processor.extractAudioLinks(content)

        links shouldBe listOf("voice_001.m4a", "music.MP3")
    }

    private fun `extractLocalAttachmentPaths should merge image and audio and skip remote urls`() {
        val content =
            """
            ![img](img_1.jpg)
            ![[img_2.png]]
            [audio](voice_001.m4a)
            ![remote](https://example.com/a.jpg)
            [remote-audio](http://example.com/b.mp3)
            """.trimIndent()

        val attachments = processor.extractLocalAttachmentPaths(content)

        attachments shouldBe listOf("img_1.jpg", "img_2.png", "voice_001.m4a")
    }
}
