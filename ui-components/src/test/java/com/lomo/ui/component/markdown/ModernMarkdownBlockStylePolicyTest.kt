package com.lomo.ui.component.markdown

import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.TypographyScales
import org.intellij.markdown.MarkdownElementTypes
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: modern markdown block text style policy.
 * - Behavior focus: quoted content must inherit quote styling rather than being rendered with the normal paragraph style.
 * - Observable outcomes: resolved text style for quote descendants versus ordinary paragraphs.
 * - Red phase: Fails before the fix because the block-quote renderer never passes quoteStyle into nested paragraph rendering.
 * - Excludes: bitmap rendering, image loading, and parser internals beyond the visible AST node type.
 */
class ModernMarkdownBlockStylePolicyTest {
    @Test
    fun `quote blocks use quote text style for nested paragraph content`() {
        val tokenSpec =
            createModernMarkdownTokenSpec(
                Typography(),
                linkColor = Color(0xFF3366FF),
                scales = TypographyScales(),
            )
        val root = parseModernMarkdownDocument("> quoted text")
        val quoteNode = root.children.first { it.type == MarkdownElementTypes.BLOCK_QUOTE }
        val paragraphNode = quoteNode.children.first { it.type == MarkdownElementTypes.PARAGRAPH }

        val resolvedQuoteStyle =
            resolveModernMarkdownBlockTextStyle(
                node = quoteNode,
                tokenSpec = tokenSpec,
                baseParagraphStyle = tokenSpec.paragraphStyle,
            )
        val resolvedParagraphStyle =
            resolveModernMarkdownBlockTextStyle(
                node = paragraphNode,
                tokenSpec = tokenSpec,
                baseParagraphStyle = tokenSpec.paragraphStyle,
            )

        assertEquals(tokenSpec.quoteStyle, resolvedQuoteStyle)
        assertEquals(tokenSpec.paragraphStyle, resolvedParagraphStyle)
    }

    @Test
    fun `quote indicator keeps rounded bar shape and uses material theme color`() {
        val colorScheme = lightColorScheme(primary = Color(0xFF6750A4))

        val indicatorStyle = resolveModernMarkdownQuoteIndicatorStyle(colorScheme)

        assertEquals(4.dp, indicatorStyle.thickness)
        assertEquals(2.dp, indicatorStyle.cornerRadius)
        assertEquals(colorScheme.primary, indicatorStyle.color)
    }
}
