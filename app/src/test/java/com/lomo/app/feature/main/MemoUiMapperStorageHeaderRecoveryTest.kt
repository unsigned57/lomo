package com.lomo.app.feature.main

import com.lomo.domain.model.Memo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * Test Contract:
 * - Unit under test: MemoUiMapper
 * - Behavior focus: display-safe recovery of memo content and timestamp when stale memo state still leaks a storage header into app-layer mapping or when plain-markdown raw content is the only non-blank source body.
 * - Observable outcomes: mapped memo timestamp, processed content, collapsed summary, markdown precompute choice for deferred rendering, and URL-only raw markdown recovery when memo.content is blank.
 * - Red phase: Fails before the fix when a stale `- <zero-width>HH:mm:ss` storage header still surfaces as `00:00:00` plus leaked header text in collapsed/expanded memo rendering, or when URL-only raw markdown still maps to blank UI content.
 * - Excludes: Compose tree rendering, Room query wiring, and repository refresh orchestration.
 */
class MemoUiMapperStorageHeaderRecoveryTest {
    private val mapper = MemoUiMapper()

    @Test
    fun `mapToUiModel recovers display content and timestamp from raw storage header when memo is stale`() {
        val rawContent =
            """
            - ​21:00:33
              #收藏/诗词
              贫穷问答歌

              山上忆良

              风雨交加夜,冷雨夹雪天。

              瑟瑟冬日晚,怎耐此夕寒。

              粗盐权佐酒,糟醅聊取暖。

              鼻塞频作响,俯首咳连连。

              捻髭空自许,难御此夜寒。

              盖我麻布衾,披我破衣衫。

              虽尽我所有,难耐此夕寒。
            """.trimIndent()
        val staleMemo =
            Memo(
                id = "2022_08_18_00:00:00_bad",
                timestamp = midnightTimestampOf(2022, 8, 18),
                updatedAt = midnightTimestampOf(2022, 8, 18),
                content = rawContent,
                rawContent = rawContent,
                dateKey = "2022_08_18",
                tags = listOf("收藏/诗词"),
            )

        val uiModel =
            mapper.mapToUiModel(
                memo = staleMemo,
                rootPath = null,
                imagePath = null,
                imageMap = emptyMap(),
                precomputeMarkdown = false,
            )

        assertNull(uiModel.precomputedRenderPlan)
        assertTrue(uiModel.shouldShowExpand)
        assertEquals(LocalDate.of(2022, 8, 18), Instant.ofEpochMilli(uiModel.memo.timestamp).atZone(ZoneId.systemDefault()).toLocalDate())
        assertEquals(LocalTime.of(21, 0, 33), Instant.ofEpochMilli(uiModel.memo.timestamp).atZone(ZoneId.systemDefault()).toLocalTime())
        assertTrue(uiModel.memo.content.contains("贫穷问答歌"))
        assertTrue(uiModel.memo.content.contains("山上忆良"))
        assertFalse(uiModel.memo.content.contains("- ​21:00:33"))
        assertFalse(uiModel.processedContent.contains("- ​21:00:33"))
        assertFalse(uiModel.collapsedSummary.contains("21:00:33"))
        assertTrue(uiModel.collapsedSummary.contains("贫穷问答歌"))
        assertTrue(uiModel.collapsedSummary.contains("山上忆良"))
    }

    @Test
    fun `mapToUiModel keeps existing content when raw storage header has no body`() {
        val memo =
            Memo(
                id = "2026_03_25_21:00:00_header_only",
                timestamp = midnightTimestampOf(2026, 3, 25),
                updatedAt = midnightTimestampOf(2026, 3, 25),
                content = "still visible body",
                rawContent = "- 21:00",
                dateKey = "2026_03_25",
                tags = emptyList(),
            )

        val uiModel =
            mapper.mapToUiModel(
                memo = memo,
                rootPath = null,
                imagePath = null,
                imageMap = emptyMap(),
                precomputeMarkdown = false,
            )

        assertEquals("still visible body", uiModel.memo.content)
        assertEquals("still visible body", uiModel.processedContent)
        assertEquals("still visible body", uiModel.collapsedSummary)
        assertEquals(LocalTime.of(21, 0), Instant.ofEpochMilli(uiModel.memo.timestamp).atZone(ZoneId.systemDefault()).toLocalTime())
    }

    @Test
    fun `mapToUiModel recovers plain markdown raw content when memo content is blank`() {
        val rawContent = "https://example.com/url-only"
        val memo =
            Memo(
                id = "2026_03_25_00:00:00_plain_markdown_blank",
                timestamp = midnightTimestampOf(2026, 3, 25),
                updatedAt = midnightTimestampOf(2026, 3, 25),
                content = "",
                rawContent = rawContent,
                dateKey = "2026_03_25",
                tags = emptyList(),
            )

        val uiModel =
            mapper.mapToUiModel(
                memo = memo,
                rootPath = null,
                imagePath = null,
                imageMap = emptyMap(),
                precomputeMarkdown = false,
            )

        assertEquals(rawContent, uiModel.memo.content)
        assertEquals(rawContent, uiModel.processedContent)
        assertEquals(rawContent, uiModel.collapsedSummary)
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
