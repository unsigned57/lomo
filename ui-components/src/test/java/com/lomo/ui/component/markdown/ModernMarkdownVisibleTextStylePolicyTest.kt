package com.lomo.ui.component.markdown

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: modern markdown visible text-style policy.
 * - Behavior focus: theme text colors must be applied to modern markdown token styles that would otherwise stay Color.Unspecified, while preserving any explicit color already set on a token.
 * - Observable outcomes: resolved paragraph, heading, list, quote, code, and table style colors.
 * - Red phase: Fails before the fix because the modern markdown token spec leaves heading and other block-text styles at Color.Unspecified, so title and similar text can render invisible on the new backend.
 * - Excludes: Compose tree rendering, third-party markdown parsing internals, and Android TextView behavior.
 */
class ModernMarkdownVisibleTextStylePolicyTest {
    @Test
    fun `unstyled modern markdown text tokens receive visible theme colors`() {
        val baseSpec = createModernMarkdownTokenSpec(Typography(), linkColor = Color(0xFF3366FF))
        val primaryTextColor = Color(0xFF223344)
        val secondaryTextColor = Color(0xFF556677)

        val resolved =
            resolveVisibleModernMarkdownTokenSpec(
                baseSpec = baseSpec,
                primaryTextColor = primaryTextColor,
                secondaryTextColor = secondaryTextColor,
            )

        assertEquals(primaryTextColor, resolved.paragraphStyle.color)
        assertEquals(primaryTextColor, resolved.heading1Style.color)
        assertEquals(primaryTextColor, resolved.heading2Style.color)
        assertEquals(primaryTextColor, resolved.heading3Style.color)
        assertEquals(primaryTextColor, resolved.heading4Style.color)
        assertEquals(primaryTextColor, resolved.heading5Style.color)
        assertEquals(secondaryTextColor, resolved.heading6Style.color)
        assertEquals(primaryTextColor, resolved.listStyle.color)
        assertEquals(primaryTextColor, resolved.quoteStyle.color)
        assertEquals(primaryTextColor, resolved.codeStyle.color)
        assertEquals(primaryTextColor, resolved.inlineCodeStyle.color)
        assertEquals(primaryTextColor, resolved.tableStyle.color)
    }

    @Test
    fun `explicit token colors survive visible color resolution`() {
        val explicitHeadingColor = Color(0xFFAA3300)
        val explicitQuoteColor = Color(0xFF0055AA)
        val baseSpec =
            createModernMarkdownTokenSpec(Typography(), linkColor = Color(0xFF3366FF)).copy(
                heading2Style =
                    createModernMarkdownTokenSpec(Typography(), linkColor = Color(0xFF3366FF))
                        .heading2Style
                        .copy(color = explicitHeadingColor),
                quoteStyle =
                    createModernMarkdownTokenSpec(Typography(), linkColor = Color(0xFF3366FF))
                        .quoteStyle
                        .copy(color = explicitQuoteColor),
            )

        val resolved =
            resolveVisibleModernMarkdownTokenSpec(
                baseSpec = baseSpec,
                primaryTextColor = Color(0xFF223344),
                secondaryTextColor = Color(0xFF556677),
            )

        assertEquals(explicitHeadingColor, resolved.heading2Style.color)
        assertEquals(explicitQuoteColor, resolved.quoteStyle.color)
    }
}
