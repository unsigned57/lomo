package com.lomo.ui.component.markdown

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: modern markdown autolink paragraph rendering.
 * - Behavior focus: a memo paragraph containing only a bare URL must still emit visible text and preserve a clickable link annotation instead of collapsing to an empty paragraph.
 * - Observable outcomes: annotated text payload, link-annotation presence, and emitted paragraph-item kinds.
 * - Red phase: Fails before the fix because the modern fragment annotator returns an empty result for a bare autolink fragment, so URL-only memo paragraphs render no visible text item.
 * - Excludes: Compose widget rendering, TextView movement methods, image loading, and top-level block planning.
 */
class ModernMarkdownAutolinkParagraphTest {
    private val tokenSpec =
        createModernMarkdownTokenSpec(
            typography = Typography(),
            linkColor = Color(0xFF3366FF),
        )

    @Test
    fun `bare url fragment keeps visible text and link annotation`() {
        val fragment = "https://example.com/path?q=1"

        val annotatedText =
            buildModernMarkdownAnnotatedTextFromFragment(
                fragment = fragment,
                style = tokenSpec.paragraphStyle,
                tokenSpec = tokenSpec,
            )

        assertEquals(fragment, annotatedText.text)
        assertTrue(annotatedText.getLinkAnnotations(0, annotatedText.length).isNotEmpty())
    }

    @Test
    fun `bare url paragraph emits a visible text item`() {
        val content = "https://example.com/path?q=1"

        val items =
            buildModernParagraphItems(
                content = content,
                paragraphNode = parseModernMarkdownDocument(content).children.single(),
                tokenSpec = tokenSpec,
                textStyle = tokenSpec.paragraphStyle,
            )

        assertEquals(1, items.size)
        val textItem = items.single() as ModernParagraphItem.Text
        assertEquals(content, textItem.text.text)
        assertFalse(textItem.text.getLinkAnnotations(0, textItem.text.length).isEmpty())
    }
}
