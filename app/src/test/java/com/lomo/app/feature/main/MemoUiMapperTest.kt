package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: MemoUiMapper
 * - Behavior focus: processed memo content maps to the shared modern markdown render plan, strips known tags only from rendered output, and invalidates cached render plans when display content changes.
 * - Observable outcomes: processed content, precomputed render-plan content, render-plan block count, collapsed summary metadata, and render-plan instance reuse behavior.
 * - Red phase: Fails before the fix because the app layer still precomputes the legacy CommonMark node path and cannot supply the unified modern render plan consumed by memo cards.
 * - Excludes: Compose card rendering, TextView paragraph layout, and repository/data-layer loading.
 */
class MemoUiMapperTest : AppFunSpec() {
    private val mapper = MemoUiMapper()

    init {
        test("mapToUiModel removes known tags from rendered body and keeps raw content") {
            val memo =
                memo(
                    content = "Meeting with C# team #work and #todo today.",
                    tags = listOf("work", "todo"),
                )

            val uiModel = mapper.mapToUiModel(memo, rootPath = null, imagePath = null, imageMap = emptyMap())
            val renderPlan = requireNotNull(uiModel.precomputedRenderPlan)
            val renderedText = renderPlan.content

            ((uiModel.processedContent.contains("#work"))) shouldBe true
            ((uiModel.processedContent.contains("#todo"))) shouldBe true
            ((renderedText.contains("C#"))) shouldBe true
            ((renderedText.contains("#work"))) shouldBe false
            ((renderedText.contains("#todo"))) shouldBe false
        }
    }

    init {
        test("mapToUiModel skips code blocks and links when erasing tags") {
            val memo =
                memo(
                    content =
                        """
                        ```kotlin
                        val raw = "#todo"
                        ```
                        [jump](https://example.com/#todo)
                        normal #todo text
                        """.trimIndent(),
                    tags = listOf("todo"),
                )

            val uiModel = mapper.mapToUiModel(memo, rootPath = null, imagePath = null, imageMap = emptyMap())
            val renderedText = requireNotNull(uiModel.precomputedRenderPlan).content

            ((renderedText.contains("val raw = \"#todo\""))) shouldBe true
            ((renderedText.contains("[jump](https://example.com/#todo)"))) shouldBe true
            ((renderedText.contains("normal #todo text"))) shouldBe false
        }
    }

    init {
        test("mapToUiModel prunes tag-only paragraph after erasing tags") {
            val memo =
                memo(
                    content =
                        """
                        #todo #work

                        body line
                        """.trimIndent(),
                    tags = listOf("todo", "work"),
                )

            val uiModel = mapper.mapToUiModel(memo, rootPath = null, imagePath = null, imageMap = emptyMap())
            val renderPlan = requireNotNull(uiModel.precomputedRenderPlan)
            val renderedText = renderPlan.content

            (renderPlan.totalBlocks) shouldBe (1)
            ((renderedText.contains("body line"))) shouldBe true
            ((renderedText.contains("#todo"))) shouldBe false
            ((renderedText.contains("#work"))) shouldBe false
        }
    }

    init {
        test("mapToUiModel removes leading blank line when first line is tag only") {
            val memo =
                memo(
                    content =
                        """
                        #todo
                        正文
                        """.trimIndent(),
                    tags = listOf("todo"),
                )

            val uiModel = mapper.mapToUiModel(memo, rootPath = null, imagePath = null, imageMap = emptyMap())
            val renderPlan = requireNotNull(uiModel.precomputedRenderPlan)
            val renderedText = renderPlan.content.trimStart()

            (renderPlan.items.size) shouldBe (1)
            ((renderedText.startsWith("正文"))) shouldBe true
        }
    }

    init {
        test("mapToUiModel resolves image cache by basename and decoded path") {
            val cachedUri = mockk<android.net.Uri>(relaxed = true)
            val memo =
                memo(
                    content = "![img](assets/foo%20bar.png)",
                    tags = emptyList(),
                )

            val uiModel =
                mapper.mapToUiModel(
                    memo = memo,
                    rootPath = "/memo",
                    imagePath = null,
                    imageMap = mapOf("foo bar.png" to cachedUri),
                )

            (uiModel.processedContent) shouldNotBe ("![img](/memo/assets/foo%20bar.png)")
        }
    }

    init {
        test("mapToUiModel keeps file scheme image url as absolute path") {
            val memo =
                memo(
                    content = "![img](file:///storage/emulated/0/Pictures/a.png)",
                    tags = emptyList(),
                )

            val uiModel =
                mapper.mapToUiModel(
                    memo = memo,
                    rootPath = "/memo",
                    imagePath = null,
                    imageMap = emptyMap(),
                )

            (uiModel.processedContent) shouldBe ("![img](file:///storage/emulated/0/Pictures/a.png)")
        }
    }

    init {
        test("mapToUiModel precomputes collapsed summary metadata") {
            val memo =
                memo(
                    content =
                        """
                        # Title
                        plain body line
                        ![img](foo.png)
                        [link](https://example.com)
                        `code`
                        extra line
                        extra line 2
                        extra line 3
                        extra line 4
                        extra line 5
                        extra line 6
                        extra line 7
                        extra line 8
                        extra line 9
                        extra line 10
                        extra line 11
                        extra line 12
                        """.trimIndent(),
                    tags = emptyList(),
                )

            val uiModel = mapper.mapToUiModel(memo, rootPath = null, imagePath = null, imageMap = emptyMap())

            ((uiModel.shouldShowExpand)) shouldBe true
            ((uiModel.collapsedSummary.contains("![img](foo.png)"))) shouldBe false
            ((uiModel.collapsedSummary.contains("[link](https://example.com)"))) shouldBe false
            ((uiModel.collapsedSummary.contains("`code`"))) shouldBe false
            ((uiModel.collapsedSummary.contains("Title"))) shouldBe true
            ((uiModel.collapsedSummary.contains("plain body line"))) shouldBe true
        }
    }

    init {
        test("mapToUiModel linkifies raw geo uri text for markdown rendering") {
            val geoUri = "geo:-29.1645,141.5243?z=10"
            val memo =
                memo(
                    content = "Meet here\n$geoUri",
                    tags = emptyList(),
                )

            val uiModel = mapper.mapToUiModel(memo, rootPath = null, imagePath = null, imageMap = emptyMap())

            ((uiModel.processedContent.contains("[$geoUri]($geoUri)"))) shouldBe true
            ((requireNotNull(uiModel.precomputedRenderPlan).content.contains(geoUri))) shouldBe true
        }
    }

    init {
        test("mapToUiModel surfaces legacy geo metadata when content has no raw geo uri") {
            val memo =
                memo(
                    content = "Body",
                    tags = emptyList(),
                    geoLocation = "-29.1645,141.5243",
                )

            val uiModel =
                mapper.mapToUiModel(
                    memo = memo,
                    rootPath = null,
                    imagePath = null,
                    imageMap = emptyMap(),
                    precomputeMarkdown = false,
                )

            (uiModel.processedContent) shouldBe ("Body\n[geo:-29.1645,141.5243?z=10](geo:-29.1645,141.5243?z=10)")
            ((uiModel.collapsedSummary.contains("geo:-29.1645,141.5243?z=10"))) shouldBe true
        }
    }

    init {
        test("mapToUiModel removes known tags from collapsed summary") {
            val memo =
                memo(
                    content =
                        """
                        #todo #work
                        plain body #todo line
                        """.trimIndent(),
                    tags = listOf("todo", "work"),
                )

            val uiModel =
                mapper.mapToUiModel(
                    memo = memo,
                    rootPath = null,
                    imagePath = null,
                    imageMap = emptyMap(),
                    precomputeMarkdown = false,
                )

            ((uiModel.collapsedSummary.contains("#todo"))) shouldBe false
            ((uiModel.collapsedSummary.contains("#work"))) shouldBe false
            ((uiModel.collapsedSummary.contains("plain body line"))) shouldBe true
        }
    }

    init {
        test("mapToUiModel reparses markdown when processed content changed") {
            val memo =
                memo(
                    content = "![img](foo.png)",
                    tags = emptyList(),
                )
            val cachedUri = mockk<android.net.Uri>(relaxed = true)

            val initial =
                mapper.mapToUiModel(
                    memo = memo,
                    rootPath = "/memo",
                    imagePath = null,
                    imageMap = emptyMap(),
                    precomputeMarkdown = true,
                )
            val initialRenderPlan = requireNotNull(initial.precomputedRenderPlan)

            val updated =
                mapper.mapToUiModel(
                    memo = memo,
                    rootPath = "/memo",
                    imagePath = null,
                    imageMap = mapOf("foo.png" to cachedUri),
                    precomputeMarkdown = true,
                    existingRenderPlan = initialRenderPlan,
                    existingProcessedContent = initial.processedContent,
                )

            (updated.processedContent) shouldNotBe (initial.processedContent)
            ((updated.precomputedRenderPlan === initialRenderPlan)) shouldBe false
        }
    }

    init {
        test("mapToUiModels reuses cached model when image map changes are unrelated to memo content") {
            runTest {
            val memo =
                memo(
                    content = "![img](foo.png)",
                    tags = emptyList(),
                )
            val cachedUri = mockk<android.net.Uri>(relaxed = true)
            val unrelatedUri = mockk<android.net.Uri>(relaxed = true)

            val initial =
                mapper.mapToUiModels(
                    memos = listOf(memo),
                    rootPath = "/memo",
                    imagePath = null,
                    imageMap = mapOf("foo.png" to cachedUri),
                )
            val updated =
                mapper.mapToUiModels(
                    memos = listOf(memo),
                    rootPath = "/memo",
                    imagePath = null,
                    imageMap = mapOf("foo.png" to cachedUri, "bar.png" to unrelatedUri),
                )

            ((initial.first() === updated.first())) shouldBe true
            ((initial.first().precomputedRenderPlan === updated.first().precomputedRenderPlan)) shouldBe true
            (updated.first().processedContent) shouldBe (initial.first().processedContent)
            }
        }
    }

    private fun memo(
        content: String,
        tags: List<String>,
        geoLocation: String? = null,
    ): Memo =
        Memo(
            id = "memo-1",
            timestamp = 0L,
            content = content,
            rawContent = content,
            dateKey = "2026_02_23",
            tags = tags,
            geoLocation = geoLocation,
        )
}
