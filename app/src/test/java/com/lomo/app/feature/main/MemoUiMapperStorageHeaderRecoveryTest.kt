package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.data.local.entity.MemoEntity
import io.kotest.matchers.shouldBe
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
class MemoUiMapperStorageHeaderRecoveryTest : AppFunSpec() {
    private val mapper = MemoUiMapper()

    init {
        test("mapToUiModel recovers display content and timestamp from raw storage header when memo is stale") {
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
                MemoEntity(
                    id = "2022_08_18_00:00:00_bad",
                    timestamp = midnightTimestampOf(2022, 8, 18),
                    content = rawContent,
                    rawContent = rawContent,
                    date = "2022_08_18",
                    tags = "收藏/诗词",
                    imageUrls = "",
                ).toDomain()

            val uiModel =
                mapper.mapToUiModel(
                    memo = staleMemo,
                    rootPath = null,
                    imagePath = null,
                    imageMap = emptyMap(),
                    precomputeMarkdown = false,
                )

            (uiModel.precomputedRenderPlan) shouldBe null
            ((uiModel.shouldShowExpand)) shouldBe true
            (Instant.ofEpochMilli(uiModel.memo.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()) shouldBe (LocalDate.of(2022, 8, 18))
            (Instant.ofEpochMilli(uiModel.memo.timestamp).atZone(ZoneId.systemDefault()).toLocalTime()) shouldBe (LocalTime.of(21, 0, 33))
            ((uiModel.memo.content.contains("贫穷问答歌"))) shouldBe true
            ((uiModel.memo.content.contains("山上忆良"))) shouldBe true
            ((uiModel.memo.content.contains("- ​21:00:33"))) shouldBe false
            ((uiModel.processedContent.contains("- ​21:00:33"))) shouldBe false
            ((uiModel.collapsedSummary.contains("21:00:33"))) shouldBe false
            ((uiModel.collapsedSummary.contains("贫穷问答歌"))) shouldBe true
            ((uiModel.collapsedSummary.contains("山上忆良"))) shouldBe true
        }
    }

    init {
        test("mapToUiModel keeps existing content when raw storage header has no body") {
            val memo =
                MemoEntity(
                    id = "2026_03_25_21:00:00_header_only",
                    timestamp = midnightTimestampOf(2026, 3, 25),
                    content = "still visible body",
                    rawContent = "- 21:00",
                    date = "2026_03_25",
                    tags = "",
                    imageUrls = "",
                ).toDomain()

            val uiModel =
                mapper.mapToUiModel(
                    memo = memo,
                    rootPath = null,
                    imagePath = null,
                    imageMap = emptyMap(),
                    precomputeMarkdown = false,
                )

            (uiModel.memo.content) shouldBe ("still visible body")
            (uiModel.processedContent) shouldBe ("still visible body")
            (uiModel.collapsedSummary) shouldBe ("still visible body")
            (Instant.ofEpochMilli(uiModel.memo.timestamp).atZone(ZoneId.systemDefault()).toLocalTime()) shouldBe (LocalTime.of(21, 0))
        }
    }

    init {
        test("mapToUiModel recovers plain markdown raw content when memo content is blank") {
            val rawContent = "https://example.com/url-only"
            val memo =
                MemoEntity(
                    id = "2026_03_25_00:00:00_plain_markdown_blank",
                    timestamp = midnightTimestampOf(2026, 3, 25),
                    content = "",
                    rawContent = rawContent,
                    date = "2026_03_25",
                    tags = "",
                    imageUrls = "",
                ).toDomain()

            val uiModel =
                mapper.mapToUiModel(
                    memo = memo,
                    rootPath = null,
                    imagePath = null,
                    imageMap = emptyMap(),
                    precomputeMarkdown = false,
                )

            (uiModel.memo.content) shouldBe (rawContent)
            (uiModel.processedContent) shouldBe (rawContent)
            (uiModel.collapsedSummary) shouldBe (rawContent)
        }
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
