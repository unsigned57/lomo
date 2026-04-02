package com.lomo.app.feature.main

import com.lomo.domain.model.Memo
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoUiMapper
 * - Behavior focus: processed memo content maps to the shared modern markdown render plan, strips known tags only from rendered output, and invalidates cached render plans when display content changes.
 * - Observable outcomes: processed content, precomputed render-plan content, render-plan block count, collapsed summary metadata, and render-plan instance reuse behavior.
 * - Red phase: Fails before the fix because the app layer still precomputes the legacy CommonMark node path and cannot supply the unified modern render plan consumed by memo cards.
 * - Excludes: Compose card rendering, TextView paragraph layout, and repository/data-layer loading.
 */
class MemoUiMapperTest {
    private val mapper = MemoUiMapper()

    @Test
    fun `mapToUiModel removes known tags from rendered body and keeps raw content`() {
        val memo =
            memo(
                content = "Meeting with C# team #work and #todo today.",
                tags = listOf("work", "todo"),
            )

        val uiModel = mapper.mapToUiModel(memo, rootPath = null, imagePath = null, imageMap = emptyMap())
        val renderPlan = requireNotNull(uiModel.precomputedRenderPlan)
        val renderedText = renderPlan.content

        assertTrue(uiModel.processedContent.contains("#work"))
        assertTrue(uiModel.processedContent.contains("#todo"))
        assertTrue(renderedText.contains("C#"))
        assertFalse(renderedText.contains("#work"))
        assertFalse(renderedText.contains("#todo"))
    }

    @Test
    fun `mapToUiModel skips code blocks and links when erasing tags`() {
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

        assertTrue(renderedText.contains("val raw = \"#todo\""))
        assertTrue(renderedText.contains("[jump](https://example.com/#todo)"))
        assertFalse(renderedText.contains("normal #todo text"))
    }

    @Test
    fun `mapToUiModel prunes tag-only paragraph after erasing tags`() {
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

        assertEquals(1, renderPlan.totalBlocks)
        assertTrue(renderedText.contains("body line"))
        assertFalse(renderedText.contains("#todo"))
        assertFalse(renderedText.contains("#work"))
    }

    @Test
    fun `mapToUiModel removes leading blank line when first line is tag only`() {
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

        assertEquals(1, renderPlan.items.size)
        assertTrue(renderedText.startsWith("正文"))
    }

    @Test
    fun `mapToUiModel resolves image cache by basename and decoded path`() {
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

        assertNotEquals("![img](/memo/assets/foo%20bar.png)", uiModel.processedContent)
    }

    @Test
    fun `mapToUiModel keeps file scheme image url as absolute path`() {
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

        assertEquals("![img](file:///storage/emulated/0/Pictures/a.png)", uiModel.processedContent)
    }

    @Test
    fun `mapToUiModel precomputes collapsed summary metadata`() {
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

        assertTrue(uiModel.shouldShowExpand)
        assertFalse(uiModel.collapsedSummary.contains("![img](foo.png)"))
        assertFalse(uiModel.collapsedSummary.contains("[link](https://example.com)"))
        assertFalse(uiModel.collapsedSummary.contains("`code`"))
        assertTrue(uiModel.collapsedSummary.contains("Title"))
        assertTrue(uiModel.collapsedSummary.contains("plain body line"))
    }

    @Test
    fun `mapToUiModel removes known tags from collapsed summary`() {
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

        assertFalse(uiModel.collapsedSummary.contains("#todo"))
        assertFalse(uiModel.collapsedSummary.contains("#work"))
        assertTrue(uiModel.collapsedSummary.contains("plain body line"))
    }

    @Test
    fun `mapToUiModel reparses markdown when processed content changed`() {
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

        assertNotEquals(initial.processedContent, updated.processedContent)
        assertFalse(updated.precomputedRenderPlan === initialRenderPlan)
    }

    private fun memo(
        content: String,
        tags: List<String>,
    ): Memo =
        Memo(
            id = "memo-1",
            timestamp = 0L,
            content = content,
            rawContent = content,
            dateKey = "2026_02_23",
            tags = tags,
        )
}
