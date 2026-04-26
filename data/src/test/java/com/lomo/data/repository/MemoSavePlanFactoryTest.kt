package com.lomo.data.repository

import com.lomo.data.parser.MarkdownParser
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.usecase.MemoIdentityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Test Contract:
 * - Unit under test: MemoSavePlanFactory
 * - Behavior focus: save-plan normalization, timestamp offsetting, collision suffix calculation, precomputed-count precedence, and inline attachment extraction coverage.
 * - Observable outcomes: generated filename, canonical timestamp, rawContent, memo id, and extracted tags or attachment URLs (images + audio links).
 * - Red phase: "create collects audio link paths in imageUrls alongside image attachments" fails before the fix because extractImages only matches `![...](..)` and `![[...]]`, dropping audio links like `[v](voice_x.m4a)`.
 * - Excludes: file I/O, repository orchestration, and UI state.
 */
class MemoSavePlanFactoryTest {
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

    @Before
    fun setUp() {
        textProcessor = MemoTextProcessor()
        memoIdentityPolicy = MemoIdentityPolicy()
        parser = MarkdownParser(textProcessor, memoIdentityPolicy)
        factory = MemoSavePlanFactory(parser, textProcessor, memoIdentityPolicy)
    }

    @Test
    fun `create uses precomputed counts to derive deterministic timestamp and collision suffix`() {
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

        assertEquals("$dateKey.md", plan.filename)
        assertEquals(dateKey, plan.dateKey)
        assertEquals(baseTimestamp + 2, plan.timestamp)
        assertEquals("- $timeString $content", plan.rawContent)
        assertEquals("${baseId}_3", plan.memo.id)
        assertEquals(listOf("release"), plan.memo.tags)
        assertEquals(listOf("img.png"), plan.memo.imageUrls)
    }

    @Test
    fun `create scans existing file to offset duplicate timestamps and append next collision suffix`() {
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

        assertEquals(baseTimestamp + 3, plan.timestamp)
        assertEquals("${baseId}_2", plan.memo.id)
        assertTrue(plan.memo.rawContent.startsWith("- $timeString "))
    }

    @Test
    fun `create keeps base timestamp and id when existing file is blank`() {
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

        assertEquals(baseTimestamp, plan.timestamp)
        assertEquals(baseId, plan.memo.id)
        assertEquals("- $timeString $content", plan.rawContent)
    }

    @Test
    fun `create collects audio link paths in imageUrls alongside image attachments`() {
        val content = "Visit ![cover](img.png) and play [voice](voice_1234.m4a)"

        val plan =
            factory.create(
                content = content,
                timestamp = timestamp,
                filenameFormat = StorageFilenameFormats.DEFAULT_PATTERN,
                timestampFormat = StorageTimestampFormats.DEFAULT_PATTERN,
                existingFileContent = "",
            )

        assertTrue(
            "imageUrls should retain image attachments: ${plan.memo.imageUrls}",
            plan.memo.imageUrls.contains("img.png"),
        )
        assertTrue(
            "imageUrls should include audio link targets so downstream cleanup can reach them: ${plan.memo.imageUrls}",
            plan.memo.imageUrls.contains("voice_1234.m4a"),
        )
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
