package com.lomo.ui.component.markdown

import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.lomo.ui.testing.UiComponentsFunSpec
import com.lomo.ui.theme.TypographyScales
import io.kotest.matchers.shouldBe
import org.intellij.markdown.MarkdownElementTypes

/*
 * Behavior Contract:
 * - Unit under test: modern markdown block text style policy.
 * - Owning layer: ui-components
 * - Priority tier: P2
 * - Capability: quote blocks use semantic markdown text and indicator tokens.
 *
 * Scenarios:
 * - Given a block quote AST node, when resolving block text style, then nested paragraphs use quote style.
 * - Given a Material color scheme, when resolving quote indicator style, then thickness, shape, and color come
 *   from semantic markdown tokens and the active color scheme.
 *
 * Observable outcomes:
 * - Resolved quote text style, paragraph text style, quote indicator thickness, shape, and color.
 *
 * TDD proof:
 * - Fails before the original quote-style fix because block quotes rendered nested paragraphs with normal
 *   paragraph style; fails before the token migration because quote indicator shape is exposed as a raw
 *   corner radius instead of the semantic shape token.
 *
 * - Excludes: bitmap rendering, image loading, and parser internals beyond the visible AST node type.
 *
 * Test Change Justification:
 * - Reason category: Design-token contract extraction.
 * - Old behavior/assertion being replaced: quote indicator exposed a raw cornerRadius value.
 * - Why old assertion is no longer correct: indicator shape is now owned by MarkdownComponentTokens as a
 *   full Shape token so components cannot recreate RoundedCornerShape locally.
 * - Coverage preserved by: the test still proves the rounded indicator visual contract through the resolved
 *   shape token and existing thickness/color assertions.
 * - Why this is not fitting the test to the implementation: the observable contract is the resolved indicator
 *   style consumed by the renderer, not the private constructor used to build it.
 */
class ModernMarkdownBlockStylePolicyTest : UiComponentsFunSpec() {
    init {
        test("quote blocks use quote text style for nested paragraph content") {
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

            (resolvedQuoteStyle) shouldBe (tokenSpec.quoteStyle)
            (resolvedParagraphStyle) shouldBe (tokenSpec.paragraphStyle)
        }

        test("quote indicator keeps rounded bar shape and uses material theme color") {
            val colorScheme = lightColorScheme(primary = Color(0xFF6750A4))

            val indicatorStyle = resolveModernMarkdownQuoteIndicatorStyle(colorScheme)

            (indicatorStyle.thickness) shouldBe (MarkdownComponentTokens.BlockQuoteStartPadding)
            (indicatorStyle.shape) shouldBe (MarkdownComponentTokens.CodeBlockShape)
            (indicatorStyle.color) shouldBe (colorScheme.primary)
        }
    }
}
