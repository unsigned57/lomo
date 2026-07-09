package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/*
 * Behavior Contract:
 * - Unit under test: MemoUiMapper
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: map recovered domain memo content and timestamps into display-safe UI models.
 *
 * Scenarios:
 * - Given a recovered domain memo whose persisted source had a storage header,
 *   when mapping to UI, then display fields use the recovered body and recovered timestamp.
 * - Given recovered content falls back to an existing visible body,
 *   when mapping to UI, then the visible body remains the displayed content.
 * - Given plain markdown source is the only non-blank domain content,
 *   when mapping to UI, then the markdown is displayed instead of a blank body.
 *
 * Observable outcomes:
 * - mapped memo timestamp, processed content, collapsed summary, markdown precompute choice,
 *   and URL-only raw markdown display content.
 *
 * TDD proof:
 * - Existing boundary audit fails because this app test imports a data-layer entity.
 * - Not applicable - test-only migration; no production change.
 *
 * Excludes:
 * - Compose tree rendering, Room/data mappers, and repository refresh orchestration.
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
            val recoveredContent =
                """
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
                memo(
                    id = "2022_08_18_00:00:00_bad",
                    timestamp = timestampOf(2022, 8, 18, 21, 0, 33),
                    content = recoveredContent,
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

            (uiModel.precomputedRenderPlan) shouldBe null
            ((uiModel.shouldShowExpand)) shouldBe true
            uiModel.memo.timestamp.toLocalDate() shouldBe LocalDate.of(2022, 8, 18)
            uiModel.memo.timestamp.toLocalTime() shouldBe LocalTime.of(21, 0, 33)
            ((uiModel.memo.content.contains("贫穷问答歌"))) shouldBe true
            ((uiModel.memo.content.contains("山上忆良"))) shouldBe true
            ((uiModel.memo.content.contains("- ​21:00:33"))) shouldBe false
            ((uiModel.processedContent.contains("- ​21:00:33"))) shouldBe false
            ((uiModel.collapsedSummary.contains("21:00:33"))) shouldBe false
            ((uiModel.collapsedSummary.contains("贫穷问答歌"))) shouldBe true
            ((uiModel.collapsedSummary.contains("山上忆良"))) shouldBe true
        }

        test("mapToUiModel keeps existing content when raw storage header has no body") {
            val memo =
                memo(
                    id = "2026_03_25_21:00:00_header_only",
                    timestamp = timestampOf(2026, 3, 25, 21, 0),
                    content = "still visible body",
                    rawContent = "- 21:00",
                    dateKey = "2026_03_25",
                )

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
            uiModel.memo.timestamp.toLocalTime() shouldBe LocalTime.of(21, 0)
        }

        test("mapToUiModel recovers plain markdown raw content when memo content is blank") {
            val rawContent = "https://example.com/url-only"
            val memo =
                memo(
                    id = "2026_03_25_00:00:00_plain_markdown_blank",
                    timestamp = midnightTimestampOf(2026, 3, 25),
                    content = rawContent,
                    rawContent = rawContent,
                    dateKey = "2026_03_25",
                )

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

    private fun memo(
        id: String,
        timestamp: Long,
        content: String,
        rawContent: String,
        dateKey: String,
        tags: List<String> = emptyList(),
    ): Memo =
        Memo(
            id = id,
            timestamp = timestamp,
            content = content,
            rawContent = rawContent,
            dateKey = dateKey,
            localDate = LocalDate.parse(dateKey.replace('_', '-')),
            tags = tags,
        )

    private fun midnightTimestampOf(
        year: Int,
        month: Int,
        day: Int,
    ): Long = timestampOf(year, month, day, 0, 0)

    private fun timestampOf(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
    ): Long =
        LocalDateTime
            .of(year, month, day, hour, minute, second)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun Long.toLocalDate(): LocalDate =
        Instant.ofEpochMilli(this)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

    private fun Long.toLocalTime(): LocalTime =
        Instant.ofEpochMilli(this)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
}
