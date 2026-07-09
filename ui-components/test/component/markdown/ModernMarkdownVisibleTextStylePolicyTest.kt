package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import com.lomo.ui.theme.TypographyScales

/*
 * Test Contract:
 * - Unit under test: modern markdown visible text-style policy.
 * - Behavior focus: theme text colors must be applied to modern markdown token styles that would otherwise stay Color.Unspecified, while preserving any explicit color already set on a token.
 * - Observable outcomes: resolved paragraph, heading, list, quote, code, and table style colors.
 * - Red phase: Fails before the fix because the modern markdown token spec leaves heading and other block-text styles at Color.Unspecified, so title and similar text can render invisible on the new backend.
 * - Excludes: Compose tree rendering, third-party markdown parsing internals, and Android TextView behavior.
 */
class ModernMarkdownVisibleTextStylePolicyTest : UiComponentsFunSpec() {
    private val defaultScales = TypographyScales()

    init {
        test("unstyled modern markdown text tokens receive visible theme colors") {
        val baseSpec =
            createModernMarkdownTokenSpec(
                Typography(),
                linkColor = Color(0xFF3366FF),
                scales = defaultScales,
            )
        val primaryTextColor = Color(0xFF223344)
        val secondaryTextColor = Color(0xFF556677)

        val resolved =
            resolveVisibleModernMarkdownTokenSpec(
                baseSpec = baseSpec,
                primaryTextColor = primaryTextColor,
                secondaryTextColor = secondaryTextColor,
            )

        (resolved.paragraphStyle.color) shouldBe (primaryTextColor)
        (resolved.heading1Style.color) shouldBe (primaryTextColor)
        (resolved.heading2Style.color) shouldBe (primaryTextColor)
        (resolved.heading3Style.color) shouldBe (primaryTextColor)
        (resolved.heading4Style.color) shouldBe (primaryTextColor)
        (resolved.heading5Style.color) shouldBe (primaryTextColor)
        (resolved.heading6Style.color) shouldBe (secondaryTextColor)
        (resolved.listStyle.color) shouldBe (primaryTextColor)
        (resolved.quoteStyle.color) shouldBe (primaryTextColor)
        (resolved.codeStyle.color) shouldBe (primaryTextColor)
        (resolved.inlineCodeStyle.color) shouldBe (primaryTextColor)
        (resolved.tableStyle.color) shouldBe (primaryTextColor)
        }
    }

    init {
        test("explicit token colors survive visible color resolution") {
        val explicitHeadingColor = Color(0xFFAA3300)
        val explicitQuoteColor = Color(0xFF0055AA)
        val baseSpec =
            createModernMarkdownTokenSpec(
                Typography(),
                linkColor = Color(0xFF3366FF),
                scales = defaultScales,
            ).copy(
                heading2Style =
                    createModernMarkdownTokenSpec(
                        Typography(),
                        linkColor = Color(0xFF3366FF),
                        scales = defaultScales,
                    )
                        .heading2Style
                        .copy(color = explicitHeadingColor),
                quoteStyle =
                    createModernMarkdownTokenSpec(
                        Typography(),
                        linkColor = Color(0xFF3366FF),
                        scales = defaultScales,
                    )
                        .quoteStyle
                        .copy(color = explicitQuoteColor),
            )

        val resolved =
            resolveVisibleModernMarkdownTokenSpec(
                baseSpec = baseSpec,
                primaryTextColor = Color(0xFF223344),
                secondaryTextColor = Color(0xFF556677),
            )

        (resolved.heading2Style.color) shouldBe (explicitHeadingColor)
        (resolved.quoteStyle.color) shouldBe (explicitQuoteColor)
        }
    }
}
