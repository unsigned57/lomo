package com.lomo.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * Test Contract:
 * - Unit under test: MemoEntity.toDomain
 * - Behavior focus: recovery of stale persisted memo content and timestamp from rawContent when older parser bugs stored fallback midnight values or left plain-markdown stored content blank.
 * - Observable outcomes: recovered domain timestamp date/time, recovered content without storage header leakage, preserved fallback content when rawContent is plain markdown, and URL-only raw markdown recovery when stored content is blank.
 * - Red phase: Fails before the fix when a stale entity created from `- <zero-width>HH:mm:ss` storage text still surfaces midnight timestamp and raw header text in the UI, or when a plain-markdown entity with blank stored content still drops its raw URL body.
 * - Excludes: Room queries, markdown rendering widgets, and sync refresh scheduling.
 */
class MemoEntityRecoveryTest {
    @Test
    fun `toDomain recovers stale midnight memo from raw content with zero width separator`() {
        val domain =
            MemoEntity(
                id = "2022_08_18_00:00:00_bad",
                timestamp = midnightTimestampOf(2022, 8, 18),
                content =
                    """
                    - ​21:00:33
                      #收藏/诗词
                      贫穷问答歌

                      山上忆良
                    """.trimIndent(),
                rawContent =
                    """
                    - ​21:00:33
                      #收藏/诗词
                      贫穷问答歌

                      山上忆良
                    """.trimIndent(),
                date = "2022_08_18",
                tags = "收藏/诗词",
                imageUrls = "",
            ).toDomain()

        assertEquals(LocalDate.of(2022, 8, 18), Instant.ofEpochMilli(domain.timestamp).atZone(ZoneId.systemDefault()).toLocalDate())
        assertEquals(LocalTime.of(21, 0, 33), Instant.ofEpochMilli(domain.timestamp).atZone(ZoneId.systemDefault()).toLocalTime())
        assertTrue(domain.content.contains("贫穷问答歌"))
        assertTrue(domain.content.contains("山上忆良"))
        assertFalse(domain.content.contains("- ​21:00:33"))
    }

    @Test
    fun `toDomain keeps plain markdown fallback content when raw content has no storage header`() {
        val content =
            """
            # Title

            plain body
            """.trimIndent()

        val domain =
            MemoEntity(
                id = "2026_03_25_00:00:00_hash",
                timestamp = midnightTimestampOf(2026, 3, 25),
                content = content,
                rawContent = content,
                date = "2026_03_25",
                tags = "",
                imageUrls = "",
            ).toDomain()

        assertEquals(content, domain.content)
        assertEquals(midnightTimestampOf(2026, 3, 25), domain.timestamp)
    }

    @Test
    fun `toDomain keeps stored content when raw storage header has no recovered body`() {
        val storedContent = "still visible body"

        val domain =
            MemoEntity(
                id = "2026_03_25_21:00:00_header_only",
                timestamp = midnightTimestampOf(2026, 3, 25),
                content = storedContent,
                rawContent = "- 21:00",
                date = "2026_03_25",
                tags = "",
                imageUrls = "",
            ).toDomain()

        assertEquals(storedContent, domain.content)
        assertEquals(LocalTime.of(21, 0), Instant.ofEpochMilli(domain.timestamp).atZone(ZoneId.systemDefault()).toLocalTime())
    }

    @Test
    fun `toDomain recovers plain markdown raw content when stored content is blank`() {
        val rawContent = "https://example.com/url-only"

        val domain =
            MemoEntity(
                id = "2026_03_25_00:00:00_plain_markdown_blank",
                timestamp = midnightTimestampOf(2026, 3, 25),
                content = "",
                rawContent = rawContent,
                date = "2026_03_25",
                tags = "",
                imageUrls = "",
            ).toDomain()

        assertEquals(rawContent, domain.content)
        assertEquals(midnightTimestampOf(2026, 3, 25), domain.timestamp)
    }

    private fun midnightTimestampOf(
        year: Int,
        month: Int,
        day: Int,
    ): Long =
        LocalDate
            .of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
