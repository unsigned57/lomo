package com.lomo.ui.text

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.lomo.ui.component.markdown.createModernMarkdownTokenSpec
import com.lomo.ui.theme.TypographyScales

/*
 * Test Contract:
 * - Unit under test: memo paragraph link-visibility contract.
 * - Behavior focus: Compose-native memo URL drawing must preserve the annotated markdown link
 *   color and keep the requested underline when the memo link contract marks URLs as visibly
 *   underlined.
 * - Observable outcomes: resolved URL visual style color and underline flag.
 * - Red phase: Fails before the fix because memo URL drawing falls back to default link visuals
 *   instead of honoring the annotated markdown span style.
 * - Excludes: actual Canvas drawing, Activity launch behavior, and Compose layout measurement.
 *
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Old behavior/assertion being replaced: the metadata described platform URL spans.
 * - Why old assertion is no longer correct: URL drawing now uses Compose-native Canvas glyph
 *   painting, not TextView spans.
 * - Coverage preserved by: the same visual-style resolver assertions and markdown token checks.
 * - Why this is not fitting the test to the implementation: the user-visible link color and
 *   underline contract is unchanged; only the rendering backend changed.
 */
class MemoParagraphLinkVisibilityContractTest : UiComponentsFunSpec() {
    private val linkColor = Color(0xFF0061A4)
    private val defaultScales = TypographyScales()

    init {
        test("compose link style keeps annotated markdown color with underline") {
        val annotated =
            buildAnnotatedString {
                pushLink(
                    LinkAnnotation.Url(
                        url = "https://example.com",
                        styles =
                            TextLinkStyles(
                                style =
                                    SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                            ),
                    ),
                )
                append("Example")
                pop()
            }

        val visualStyle =
            resolveMemoUrlVisualStyle(
                text = annotated,
                start = 0,
                end = annotated.length,
                defaultColor = Color.Magenta,
                defaultUnderline = true,
            )

        (visualStyle.color) shouldBe (linkColor)
        (visualStyle.isUnderlineText) shouldBe (true)
        }
    }

    init {
        test("modern markdown tokens expose underlined link style") {
        val spec =
            createModernMarkdownTokenSpec(
                Typography(),
                linkColor = linkColor,
                scales = defaultScales,
            )

        (spec.linkStyle.style?.color) shouldBe (linkColor)
        (spec.linkStyle.style?.textDecoration) shouldBe (TextDecoration.Underline)
        }
    }
}
