package com.lomo.ui.component.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: markdown paragraph text style resolution.
 * - Behavior focus: markdown text handed to MemoParagraphText must resolve Color.Unspecified to a visible fallback color while preserving explicit color and text decoration.
 * - Observable outcomes: resolved style color and retained text decoration.
 * - Red phase: Fails before the fix because the modern markdown renderer forwards Color.Unspecified into TextView-backed paragraph rendering, making memo text and unchecked todo labels disappear.
 * - Excludes: Compose tree rendering, Android TextView internals, and markdown AST parsing.
 */
class MarkdownTextStyleResolverTest {
    @Test
    fun `unspecified markdown text color falls back to visible theme color`() {
        val fallbackColor = Color(0xFF223344)

        val resolved =
            resolveMarkdownParagraphTextStyle(
                baseStyle = TextStyle(color = Color.Unspecified),
                fallbackColor = fallbackColor,
                text = "未完成 todo item",
            )

        assertEquals(fallbackColor, resolved.color)
        assertNotEquals(Color.Unspecified, resolved.color)
    }

    @Test
    fun `explicit markdown text color and decoration stay unchanged`() {
        val explicitColor = Color(0xFF667788)
        val fallbackColor = Color(0xFF223344)

        val resolved =
            resolveMarkdownParagraphTextStyle(
                baseStyle =
                    TextStyle(
                        color = explicitColor,
                        textDecoration = TextDecoration.LineThrough,
                    ),
                fallbackColor = fallbackColor,
                text = "已完成 todo item",
            )

        assertEquals(explicitColor, resolved.color)
        assertEquals(TextDecoration.LineThrough, resolved.textDecoration)
    }
}
