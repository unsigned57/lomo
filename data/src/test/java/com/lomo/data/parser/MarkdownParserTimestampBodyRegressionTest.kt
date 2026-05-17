package com.lomo.data.parser


import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.usecase.MemoIdentityPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Test Contract:
 * - Unit under test: MarkdownParser
 * - Behavior focus: storage-header parsing for timestamp-only first lines, including files that carry an invisible UTF-8 BOM before the first memo header.
 * - Observable outcomes: parsed memo count, resolved timestamp date/time, and preserved body text after removing the storage header.
 * - Red phase: Fails before the fix when an invisible BOM causes the first `- HH:mm:ss` storage header to miss parsing, so the memo falls back to plain Markdown content and loses the intended timestamp.
 * - Excludes: Compose rendering, Room persistence, and sync transport orchestration.
 */
class MarkdownParserTimestampBodyRegressionTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("parseContent keeps tagged poem body when header line only contains timestamp") { `parseContent keeps tagged poem body when header line only contains timestamp`() }

        test("parseContent ignores leading bom before timestamp-only header") { `parseContent ignores leading bom before timestamp-only header`() }

        test("parseContent ignores zero width separator between dash and timestamp") { `parseContent ignores zero width separator between dash and timestamp`() }

        test("parseContent keeps poem body when zero width separator header is followed by tag only line and blank line") { `parseContent keeps poem body when zero width separator header is followed by tag only line and blank line`() }

        test("parseContent keeps long poem body when zero width separator header has no inline content") { `parseContent keeps long poem body when zero width separator header has no inline content`() }
    }


    private lateinit var parser: MarkdownParser

    private fun setUp() {
        parser = MarkdownParser(MemoTextProcessor(), MemoIdentityPolicy())
    }

    private fun `parseContent keeps tagged poem body when header line only contains timestamp`() {
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

        memos.size shouldBe 1
        (memos.single().content.contains("#收藏/诗词")).shouldBeTrue()
        (memos.single().content.contains("酌酒与裴迪")).shouldBeTrue()
        Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalDate() shouldBe LocalDate.of(2026, 3, 26)
        Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalTime() shouldBe LocalTime.of(17, 56, 16)
    }

    private fun `parseContent ignores leading bom before timestamp-only header`() {
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

        memos.size shouldBe 1
        (memos.single().content.contains("#收藏/诗词")).shouldBeTrue()
        (memos.single().content.contains("酌酒与裴迪")).shouldBeTrue()
        (!memos.single().content.contains("- 17:56:16")).shouldBeTrue()
        Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalTime() shouldBe LocalTime.of(17, 56, 16)
    }

    private fun `parseContent ignores zero width separator between dash and timestamp`() {
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

        memos.size shouldBe 1
        (memos.single().content.contains("#收藏/诗词")).shouldBeTrue()
        (memos.single().content.contains("酌酒与裴迪")).shouldBeTrue()
        (memos.single().content.contains("唐 王维")).shouldBeTrue()
        (!memos.single().content.contains("- ​17:56:16")).shouldBeTrue()
        Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalDate() shouldBe LocalDate.of(2022, 9, 7)
        Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalTime() shouldBe LocalTime.of(17, 56, 16)
    }

    private fun `parseContent keeps poem body when zero width separator header is followed by tag only line and blank line`() {
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

        memos.size shouldBe 1
        (memos.single().content.contains("#收藏/诗词")).shouldBeTrue()
        (memos.single().content.contains("妾在巫山之阳")).shouldBeTrue()
        (!memos.single().content.contains("- ​18:21:25")).shouldBeTrue()
        Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalDate() shouldBe LocalDate.of(2022, 8, 28)
        Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalTime() shouldBe LocalTime.of(18, 21, 25)
    }

    private fun `parseContent keeps long poem body when zero width separator header has no inline content`() {
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

        memos.size shouldBe 1
        (memos.single().content.contains("贫穷问答歌")).shouldBeTrue()
        (memos.single().content.contains("山上忆良")).shouldBeTrue()
        (memos.single().content.contains("风雨交加夜")).shouldBeTrue()
        (!memos.single().content.contains("- ​21:00:33")).shouldBeTrue()
        Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalDate() shouldBe LocalDate.of(2022, 8, 18)
        Instant.ofEpochMilli(memos.single().timestamp).atZone(ZoneId.systemDefault()).toLocalTime() shouldBe LocalTime.of(21, 0, 33)
    }
}
