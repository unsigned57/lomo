package com.lomo.ui.component.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given an unspecified Markdown text color, when style is resolved, then the visible theme fallback is
 *   used.
 * - Given explicit color and decoration, when style is resolved, then both explicit properties are preserved.
 * - Unit under test: markdown paragraph text style resolution.
 * - Capability: markdown text handed to MemoParagraphText must resolve Color.Unspecified to a visible fallback color while preserving explicit color and text decoration.
 * - Observable outcomes: resolved style color and retained text decoration.
 * - TDD proof: Fails before the fix because the modern markdown renderer forwards Color.Unspecified into TextView-backed paragraph rendering, making memo text and unchecked todo labels disappear.
 * - Excludes: Compose tree rendering, Android TextView internals, and markdown AST parsing.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for MarkdownTextStyleResolver.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including unspecified markdown text color falls back to
 * visible theme color; explicit markdown text color and decoration stay unchanged.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class MarkdownTextStyleResolverTest : UiComponentsFunSpec() {
    init {
        test("unspecified markdown text color falls back to visible theme color") {
            val fallbackColor = Color(0xFF223344)

            val resolved =
                resolveMarkdownParagraphTextStyle(
                    baseStyle = TextStyle(color = Color.Unspecified),
                    fallbackColor = fallbackColor,
                    text = "未完成 todo item",
                )

            (resolved.color) shouldBe (fallbackColor)
            (Color.Unspecified) shouldNotBe (resolved.color)
        }
    }

    init {
        test("explicit markdown text color and decoration stay unchanged") {
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

            (resolved.color) shouldBe (explicitColor)
            (resolved.textDecoration) shouldBe (TextDecoration.LineThrough)
        }
    }
}
