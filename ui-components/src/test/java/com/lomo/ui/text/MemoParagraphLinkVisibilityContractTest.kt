package com.lomo.ui.text

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import com.lomo.ui.component.markdown.createModernMarkdownTokenSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo paragraph link-visibility contract.
 * - Behavior focus: platform URL spans must preserve the annotated markdown link color and avoid
 *   reintroducing an underline when the Material 3 token contract chooses a cleaner link style.
 * - Observable outcomes: resolved platform URL visual style color and underline flag.
 * - Red phase: Fails before the fix because MemoUrlSpan falls back to TextView link defaults,
 *   which can repaint URLs with the wrong color and force an underline regardless of the
 *   annotated markdown span style.
 * - Excludes: actual TextView drawing, Activity launch behavior, and Compose layout measurement.
 */
class MemoParagraphLinkVisibilityContractTest {
    private val linkColor = Color(0xFF0061A4)

    @Test
    fun `platform link style keeps annotated markdown color without underline`() {
        val annotated =
            buildAnnotatedString {
                pushLink(
                    LinkAnnotation.Url(
                        url = "https://example.com",
                        styles = TextLinkStyles(style = SpanStyle(color = linkColor)),
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

        assertEquals(linkColor, visualStyle.color)
        assertEquals(false, visualStyle.isUnderlineText)
    }

    @Test
    fun `modern markdown tokens expose clean non underlined link style`() {
        val spec = createModernMarkdownTokenSpec(Typography(), linkColor = linkColor)

        assertEquals(linkColor, spec.linkStyle.style?.color)
        assertEquals(
            null,
            spec.linkStyle.style?.textDecoration,
        )
    }
}
