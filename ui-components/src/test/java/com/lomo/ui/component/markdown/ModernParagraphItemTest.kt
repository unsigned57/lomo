package com.lomo.ui.component.markdown

import androidx.compose.material3.Typography
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: modern markdown paragraph item builder.
 * - Behavior focus: plain and mixed-content memo paragraphs must emit visible text items instead of collapsing to an empty body, while inline images still split into dedicated media items.
 * - Observable outcomes: emitted paragraph item kinds, text payloads, and image destinations.
 * - Red phase: Fails before the fix because leaf text nodes in the modern paragraph path produce empty annotated strings, so normal memo bodies render no paragraph items at all.
 * - Excludes: Compose widget rendering, TextView layout, image loading, and top-level block planning.
 */
class ModernParagraphItemTest {
    private val tokenSpec = createModernMarkdownTokenSpec(Typography())

    @Test
    fun `plain text paragraph emits a visible text item`() {
        val content = "plain memo body"

        val items = buildModernParagraphItemsFor(content)

        assertEquals(1, items.size)
        val textItem = items.single() as ModernParagraphItem.Text
        assertEquals("plain memo body", textItem.text.text)
    }

    @Test
    fun `rich inline paragraph keeps visible text`() {
        val content = "今天 **bold** memo"

        val items = buildModernParagraphItemsFor(content)

        assertEquals(1, items.size)
        val textItem = items.single() as ModernParagraphItem.Text
        assertTrue(textItem.text.text.contains("今天"))
        assertTrue(textItem.text.text.contains("bold"))
        assertTrue(textItem.text.text.contains("memo"))
    }

    @Test
    fun `single newline inside a paragraph stays as a visible line break`() {
        val content = "第一行\n第二行"

        val items = buildModernParagraphItemsFor(content)

        assertEquals(1, items.size)
        val textItem = items.single() as ModernParagraphItem.Text
        assertEquals("第一行\n第二行", textItem.text.text)
    }

    @Test
    fun `single newline keeps rich inline formatting while preserving the line break`() {
        val content = "第一行 **bold**\n第二行"

        val items = buildModernParagraphItemsFor(content)

        assertEquals(1, items.size)
        val textItem = items.single() as ModernParagraphItem.Text
        assertEquals("第一行 bold\n第二行", textItem.text.text)
        assertTrue(textItem.text.spanStyles.any { it.item.fontWeight != null })
    }

    @Test
    fun `paragraph with leading text and trailing image keeps both text and image items`() {
        val content = "lead text ![cover](cover.png)"

        val items = buildModernParagraphItemsFor(content)

        assertEquals(2, items.size)
        val textItem = items.first() as ModernParagraphItem.Text
        val imageItem = items[1] as ModernParagraphItem.Image
        assertEquals("lead text ", textItem.text.text)
        assertEquals("cover.png", imageItem.image.destination)
    }

    @Test
    fun `image only paragraph still emits a dedicated image item`() {
        val content = "![cover](cover.png)"

        val items = buildModernParagraphItemsFor(content)

        assertEquals(1, items.size)
        val imageItem = items.single() as ModernParagraphItem.Image
        assertEquals("cover.png", imageItem.image.destination)
    }

    private fun buildModernParagraphItemsFor(content: String): List<ModernParagraphItem> =
        buildModernParagraphItems(
            content = content,
            paragraphNode = parseParagraphNode(content),
            tokenSpec = tokenSpec,
            textStyle = tokenSpec.paragraphStyle,
        )

    private fun parseParagraphNode(content: String): ASTNode =
        parseModernMarkdownDocument(content).children.first { it.type == MarkdownElementTypes.PARAGRAPH }
}
