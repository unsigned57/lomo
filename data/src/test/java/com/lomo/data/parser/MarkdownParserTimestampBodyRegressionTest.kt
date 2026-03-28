package com.lomo.data.parser

import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.usecase.MemoIdentityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * Test Contract:
 * - Unit under test: MarkdownParser
 * - Behavior focus: storage-header parsing for timestamp-only first lines, including files that carry an invisible UTF-8 BOM before the first memo header.
 * - Observable outcomes: parsed memo count, resolved timestamp date/time, and preserved body text after removing the storage header.
 * - Red phase: Fails before the fix when an invisible BOM causes the first `- HH:mm:ss` storage header to miss parsing, so the memo falls back to plain Markdown content and loses the intended timestamp.
 * - Excludes: Compose rendering, Room persistence, and sync transport orchestration.
 */
class MarkdownParserTimestampBodyRegressionTest {
    private lateinit var parser: MarkdownParser

    @Before
    fun setUp() {
        parser = MarkdownParser(MemoTextProcessor(), MemoIdentityPolicy())
    }

    @Test
    fun `parseContent keeps tagged poem body when header line only contains timestamp`() {
        val memos =
            parser.parseContent(
                content =
                    """
                    - 17:56:16
                      #收藏/诗词
                      酌酒与裴迪
                    """.trimIndent(),
                filename = "2026_03_26",
            )

        assertEquals(1, memos.size)
        assertTrue(memos.single().content.contains("#收藏/诗词"))
        assertTrue(memos.single().content.contains("酌酒与裴迪"))
        assertEquals(
            LocalDate.of(2026, 3, 26),
            Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalDate(),
        )
        assertEquals(
            LocalTime.of(17, 56, 16),
            Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalTime(),
        )
    }

    @Test
    fun `parseContent ignores leading bom before timestamp-only header`() {
        val memos =
            parser.parseContent(
                content =
                    """
                    ﻿- 17:56:16
                      #收藏/诗词
                      酌酒与裴迪
                    """.trimIndent(),
                filename = "2026_03_26",
            )

        assertEquals(1, memos.size)
        assertTrue(memos.single().content.contains("#收藏/诗词"))
        assertTrue(memos.single().content.contains("酌酒与裴迪"))
        assertTrue(!memos.single().content.contains("- 17:56:16"))
        assertEquals(
            LocalTime.of(17, 56, 16),
            Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalTime(),
        )
    }

    @Test
    fun `parseContent ignores zero width separator between dash and timestamp`() {
        val memos =
            parser.parseContent(
                content =
                    """
                    - ​17:56:16
                      #收藏/诗词
                      酌酒与裴迪
                      唐 王维
                    """.trimIndent(),
                filename = "2022_09_07",
            )

        assertEquals(1, memos.size)
        assertTrue(memos.single().content.contains("#收藏/诗词"))
        assertTrue(memos.single().content.contains("酌酒与裴迪"))
        assertTrue(memos.single().content.contains("唐 王维"))
        assertTrue(!memos.single().content.contains("- ​17:56:16"))
        assertEquals(
            LocalDate.of(2022, 9, 7),
            Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalDate(),
        )
        assertEquals(
            LocalTime.of(17, 56, 16),
            Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalTime(),
        )
    }

    @Test
    fun `parseContent keeps poem body when zero width separator header is followed by tag only line and blank line`() {
        val memos =
            parser.parseContent(
                content =
                    """
                    - ​18:21:25
                      #收藏/诗词

                      妾在巫山之阳,高丘之阻,旦为朝云,暮为行雨,朝朝暮暮,阳台之下
                    """.trimIndent(),
                filename = "2022_08_28",
            )

        assertEquals(1, memos.size)
        assertTrue(memos.single().content.contains("#收藏/诗词"))
        assertTrue(memos.single().content.contains("妾在巫山之阳"))
        assertTrue(!memos.single().content.contains("- ​18:21:25"))
        assertEquals(
            LocalDate.of(2022, 8, 28),
            Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalDate(),
        )
        assertEquals(
            LocalTime.of(18, 21, 25),
            Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalTime(),
        )
    }

    @Test
    fun `parseContent keeps long poem body when zero width separator header has no inline content`() {
        val memos =
            parser.parseContent(
                content =
                    """
                    - ​21:00:33
                      #收藏/诗词
                      贫穷问答歌

                      山上忆良

                      风雨交加夜,冷雨夹雪天。
                    """.trimIndent(),
                filename = "2022_08_18",
            )

        assertEquals(1, memos.size)
        assertTrue(memos.single().content.contains("贫穷问答歌"))
        assertTrue(memos.single().content.contains("山上忆良"))
        assertTrue(memos.single().content.contains("风雨交加夜"))
        assertTrue(!memos.single().content.contains("- ​21:00:33"))
        assertEquals(
            LocalDate.of(2022, 8, 18),
            Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalDate(),
        )
        assertEquals(
            LocalTime.of(21, 0, 33),
            Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalTime(),
        )
    }
}
