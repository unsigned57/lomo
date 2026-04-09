package com.lomo.ui.component.markdown

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: modern markdown inline extension formatter contract.
 * - Behavior focus: display-time inline markdown extensions must render highlight and underline styling without corrupting plain text or code-span literals.
 * - Observable outcomes: rendered annotated text content, highlight span background, underline span decoration, and absence of extension styling inside code spans.
 * - Red phase: Fails before the fix because modern markdown annotation leaves `==highlight==` and `<u>underline</u>` as plain literal text instead of rendering extension styles.
 * - Excludes: Compose widget rendering, block-level markdown layout, and third-party parser internals beyond observable annotated output.
 */
class ModernMarkdownInlineExtensionFormatterTest {
    private val typography = Typography()
    private val tokenSpec = createModernMarkdownTokenSpec(typography, linkColor = Color(0xFF0061A4))
    private val paragraphStyle: TextStyle = tokenSpec.paragraphStyle

    @Test
    fun `highlight extension renders background span and strips marker syntax`() {
        val result =
            buildModernMarkdownAnnotatedTextFromFragment(
                fragment = "Before ==focus== after",
                style = paragraphStyle,
                tokenSpec = tokenSpec,
            )

        assertEquals("Before focus after", result.text)
        val highlightRange = result.spanStyles.single { it.item.background != Color.Unspecified }
        assertEquals("focus", result.text.substring(highlightRange.start, highlightRange.end))
    }

    @Test
    fun `underline html extension renders underline span and strips tags`() {
        val result =
            buildModernMarkdownAnnotatedTextFromFragment(
                fragment = "Keep <u>focus</u> visible",
                style = paragraphStyle,
                tokenSpec = tokenSpec,
            )

        assertEquals("Keep focus visible", result.text)
        val underlineRange =
            result.spanStyles.single { it.item.textDecoration == TextDecoration.Underline }
        assertEquals("focus", result.text.substring(underlineRange.start, underlineRange.end))
    }

    @Test
    fun `inline code literals keep extension markers as plain text`() {
        val result =
            buildModernMarkdownAnnotatedTextFromFragment(
                fragment = "`==code==` and `<u>tag</u>`",
                style = paragraphStyle,
                tokenSpec = tokenSpec,
            )

        assertTrue(result.text.contains("==code=="))
        assertTrue(result.text.contains("tag"))
        assertTrue(result.spanStyles.any { it.item.fontFamily != null })
        assertFalse(result.spanStyles.any { it.item.background != Color.Unspecified })
        assertFalse(result.spanStyles.any { it.item.textDecoration == TextDecoration.Underline })
    }
}
