package com.lomo.data.repository

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



import com.lomo.data.parser.MarkdownParser
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.usecase.MemoIdentityPolicy
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: MemoSavePlanFactory
 * - Behavior focus: save-plan normalization, timestamp offsetting, collision suffix calculation, precomputed-count precedence, and inline attachment extraction coverage.
 * - Observable outcomes: generated filename, canonical timestamp, rawContent, memo id, and extracted tags or attachment URLs (images + audio links).
 * - TDD proof: "create collects audio link paths in imageUrls alongside image attachments" fails before the fix because extractImages only matches `![...](..)` and `![[...]]`, dropping audio links like `[v](voice_x.m4a)`.
 * - Excludes: file I/O, repository orchestration, and UI state.
 */
class MemoSavePlanFactoryTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("create uses precomputed counts to derive deterministic timestamp and collision suffix") { `create uses precomputed counts to derive deterministic timestamp and collision suffix`() }

        test("create scans existing file to offset duplicate timestamps and append next collision suffix") { `create scans existing file to offset duplicate timestamps and append next collision suffix`() }

        test("create keeps base timestamp and id when existing file is blank") { `create keeps base timestamp and id when existing file is blank`() }

        test("create collects audio link paths in imageUrls alongside image attachments") { `create collects audio link paths in imageUrls alongside image attachments`() }
    }


    private lateinit var parser: MarkdownParser
    private lateinit var textProcessor: MemoTextProcessor
    private lateinit var memoIdentityPolicy: MemoIdentityPolicy
    private lateinit var factory: MemoSavePlanFactory

    private val timestamp =
        LocalDateTime
            .of(2026, 3, 27, 9, 15, 30)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun setUp() {
        textProcessor = MemoTextProcessor()
        memoIdentityPolicy = MemoIdentityPolicy()
        parser = MarkdownParser(textProcessor, memoIdentityPolicy)
        factory = MemoSavePlanFactory(parser, textProcessor, memoIdentityPolicy)
    }

    private fun `create uses precomputed counts to derive deterministic timestamp and collision suffix`() {
        val content = "Review #release ![cover](img.png)"
        val dateKey = expectedDateKey()
        val timeString = expectedTimeString()
        val baseTimestamp = parser.resolveTimestamp(dateKey, timeString, timestamp)
        val baseId = memoIdentityPolicy.buildBaseId(dateKey, timeString, content)

        val plan =
            factory.create(
                content = content,
                timestamp = timestamp,
                filenameFormat = StorageFilenameFormats.DEFAULT_PATTERN,
                timestampFormat = StorageTimestampFormats.DEFAULT_PATTERN,
                existingFileContent = "- 00:00:00 ignored",
                precomputedSameTimestampCount = 2,
                precomputedCollisionCount = 3,
            )

        plan.filename shouldBe "$dateKey.md"
        plan.dateKey shouldBe dateKey
        plan.timestamp shouldBe baseTimestamp + 2
        plan.rawContent shouldBe "- $timeString $content"
        plan.memo.id shouldBe "${baseId}_3"
        plan.memo.tags shouldBe listOf("release")
        plan.memo.imageUrls shouldBe listOf("img.png")
    }

    private fun `create scans existing file to offset duplicate timestamps and append next collision suffix`() {
        val content = "Repeated #tag ![a](a.png)"
        val dateKey = expectedDateKey()
        val timeString = expectedTimeString()
        val baseTimestamp = parser.resolveTimestamp(dateKey, timeString, timestamp)
        val baseId = memoIdentityPolicy.buildBaseId(dateKey, timeString, content)
        val existingFileContent =
            """
            - $timeString $content
            - $timeString Other memo
            - $timeString $content
            """.trimIndent()

        val plan =
            factory.create(
                content = content,
                timestamp = timestamp,
                filenameFormat = StorageFilenameFormats.DEFAULT_PATTERN,
                timestampFormat = StorageTimestampFormats.DEFAULT_PATTERN,
                existingFileContent = existingFileContent,
            )

        plan.timestamp shouldBe baseTimestamp + 3
        plan.memo.id shouldBe "${baseId}_2"
        (plan.memo.rawContent.startsWith("- $timeString ")).shouldBeTrue()
    }

    private fun `create keeps base timestamp and id when existing file is blank`() {
        val content = "Fresh memo"
        val dateKey = expectedDateKey()
        val timeString = expectedTimeString()
        val baseTimestamp = parser.resolveTimestamp(dateKey, timeString, timestamp)
        val baseId = memoIdentityPolicy.buildBaseId(dateKey, timeString, content)

        val plan =
            factory.create(
                content = content,
                timestamp = timestamp,
                filenameFormat = StorageFilenameFormats.DEFAULT_PATTERN,
                timestampFormat = StorageTimestampFormats.DEFAULT_PATTERN,
                existingFileContent = "",
            )

        plan.timestamp shouldBe baseTimestamp
        plan.memo.id shouldBe baseId
        plan.rawContent shouldBe "- $timeString $content"
    }

    private fun `create collects audio link paths in imageUrls alongside image attachments`() {
        val content = "Visit ![cover](img.png) and play [voice](voice_1234.m4a)"

        val plan =
            factory.create(
                content = content,
                timestamp = timestamp,
                filenameFormat = StorageFilenameFormats.DEFAULT_PATTERN,
                timestampFormat = StorageTimestampFormats.DEFAULT_PATTERN,
                existingFileContent = "",
            )

        withClue("imageUrls should retain image attachments: ${plan.memo.imageUrls}") { (plan.memo.imageUrls.contains("img.png")).shouldBeTrue() }
        withClue("imageUrls should include audio link targets so downstream cleanup can reach them: ${plan.memo.imageUrls}") { (plan.memo.imageUrls.contains("voice_1234.m4a")).shouldBeTrue() }
    }

    private fun expectedDateKey(): String =
        StorageFilenameFormats
            .formatter(StorageFilenameFormats.DEFAULT_PATTERN)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))

    private fun expectedTimeString(): String =
        StorageTimestampFormats
            .formatter(StorageTimestampFormats.DEFAULT_PATTERN)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
}
