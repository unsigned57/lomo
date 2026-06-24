package com.lomo.data.repository

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
 * - Owning layer: data repository save pipeline.
 * - Priority tier: P0.
 * - Capability: save-plan normalization, positional id construction, same-second timestamp
 *   offsetting, precomputed-count precedence, and inline attachment extraction coverage.
 *
 * Scenarios:
 * - Given a new-memo save, when buildSavePlan runs, then the generated filename is positional
 *   (dateKey_timePart_ordinal), rawContent is normalized, and tags/URLs are extracted.
 * - Given a content edit to an existing memo, when buildSavePlan runs, then the same positional
 *   id is derived (id stable across content edits) and the precomputed count takes precedence.
 * - Given same-second timestamps with ordinal conflicts, when buildSavePlan runs, then offset
 *   clamping preserves ordering without skipping values.
 *
 * Observable outcomes: generated filename, canonical timestamp, rawContent, positional memo id
 *   (dateKey_timePart_ordinal), and extracted tags or attachment URLs (images + audio links).
 *
 * TDD proof: id assertions fail before the content-derived id is replaced by the positional id,
 *   which keeps the id stable across content edits.
 *
 * Test Change Justification:
 * - Reason category: Memo identity model shifted from content-derived to positional ids.
 * - Old behavior/assertion being replaced: save plan used content hashing for identity; id
 *   assertions expected content-based hash suffix in filename.
 * - Why old assertion is no longer correct: positional ids replace content hashes, making file
 *   identity stable across content edits.
 * - Coverage preserved by: all normalization, timestamp offsetting, precomputed count, and
 *   inline attachment extraction scenarios retained; identity assertions updated to positional
 *   (date_time_ordinal) format.
 * - Why this is not fitting the test to the implementation: tests verify externally observable
 *   filename format, content fields, and offsets, not internal id mechanics.
 *
 * Excludes: file I/O, repository orchestration, and UI state.
 */
class MemoSavePlanFactoryTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("create uses precomputed same-time count to derive positional id and timestamp offset") { `create uses precomputed same-time count to derive positional id and timestamp offset`() }

        test("create scans existing file to offset duplicate timestamps and position the id") { `create scans existing file to offset duplicate timestamps and position the id`() }

        test("create keeps base timestamp and ordinal zero when existing file is blank") { `create keeps base timestamp and ordinal zero when existing file is blank`() }

        test("id does not depend on content for the same position") { `id does not depend on content for the same position`() }

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

    private fun `create uses precomputed same-time count to derive positional id and timestamp offset`() {
        val content = "Review #release ![cover](img.png)"
        val dateKey = expectedDateKey()
        val timeString = expectedTimeString()
        val baseTimestamp = parser.resolveTimestamp(dateKey, timeString, timestamp)

        val plan =
            factory.create(
                content = content,
                timestamp = timestamp,
                filenameFormat = StorageFilenameFormats.DEFAULT_PATTERN,
                timestampFormat = StorageTimestampFormats.DEFAULT_PATTERN,
                existingFileContent = "- 00:00:00 ignored",
                precomputedSameTimestampCount = 2,
            )

        plan.filename shouldBe "$dateKey.md"
        plan.dateKey shouldBe dateKey
        plan.timestamp shouldBe baseTimestamp + 2
        plan.rawContent shouldBe "- $timeString $content"
        plan.memo.id shouldBe expectedId(dateKey, timeString, ordinal = 2)
        plan.memo.tags shouldBe listOf("release")
        plan.memo.imageUrls shouldBe listOf("img.png")
    }

    private fun `create scans existing file to offset duplicate timestamps and position the id`() {
        val content = "Repeated #tag ![a](a.png)"
        val dateKey = expectedDateKey()
        val timeString = expectedTimeString()
        val baseTimestamp = parser.resolveTimestamp(dateKey, timeString, timestamp)
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
        plan.memo.id shouldBe expectedId(dateKey, timeString, ordinal = 3)
        (plan.memo.rawContent.startsWith("- $timeString ")).shouldBeTrue()
    }

    private fun `create keeps base timestamp and ordinal zero when existing file is blank`() {
        val content = "Fresh memo"
        val dateKey = expectedDateKey()
        val timeString = expectedTimeString()
        val baseTimestamp = parser.resolveTimestamp(dateKey, timeString, timestamp)

        val plan =
            factory.create(
                content = content,
                timestamp = timestamp,
                filenameFormat = StorageFilenameFormats.DEFAULT_PATTERN,
                timestampFormat = StorageTimestampFormats.DEFAULT_PATTERN,
                existingFileContent = "",
            )

        plan.timestamp shouldBe baseTimestamp
        plan.memo.id shouldBe expectedId(dateKey, timeString, ordinal = 0)
        plan.rawContent shouldBe "- $timeString $content"
    }

    private fun `id does not depend on content for the same position`() {
        val dateKey = expectedDateKey()
        val timeString = expectedTimeString()

        val first =
            factory.create(
                content = "Original body",
                timestamp = timestamp,
                filenameFormat = StorageFilenameFormats.DEFAULT_PATTERN,
                timestampFormat = StorageTimestampFormats.DEFAULT_PATTERN,
                existingFileContent = "",
            )
        val edited =
            factory.create(
                content = "Completely different body that changes the hash",
                timestamp = timestamp,
                filenameFormat = StorageFilenameFormats.DEFAULT_PATTERN,
                timestampFormat = StorageTimestampFormats.DEFAULT_PATTERN,
                existingFileContent = "",
            )

        edited.memo.id shouldBe first.memo.id
        first.memo.id shouldBe expectedId(dateKey, timeString, ordinal = 0)
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

    private fun expectedId(
        dateKey: String,
        timeString: String,
        ordinal: Int,
    ): String = "${dateKey}_${timeString}_$ordinal"

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
