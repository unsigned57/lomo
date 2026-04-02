package com.lomo.ui.text

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.memoBodyTextStyle
import com.lomo.ui.theme.memoEditorTextStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: raw memo plain-text presentation spec for editor/display parity.
 * - Behavior focus: editor-side raw text and rendered memo body text must resolve through the same paragraph splitter, spacing token, and script-aware body style so plain prose looks identical without interpreting Markdown syntax.
 * - Observable outcomes: split raw paragraphs, preserved markdown marker literals, resolved shared text style, and shared paragraph spacing.
 * - Red phase: Fails before the fix because the project has no shared raw memo presentation spec, so editor text and rendered memo body text cannot be proven to share the same paragraph policy.
 * - Excludes: Compose widget-tree rendering, TextView/EditText measurement internals, IME integration, and markdown semantic parsing.
 */
class MemoPlainTextPresentationTest {
    private val typography = Typography()

    @Test
    fun `raw memo paragraph splitter keeps intra paragraph line breaks and splits only on blank lines`() {
        val result =
            splitRawMemoParagraphs(
                "第一行\n第二行\n\n# 标题语法仍是普通文本\n- 列表语法也只是字符\n\n最后一段",
            )

        assertEquals(
            listOf(
                "第一行\n第二行",
                "# 标题语法仍是普通文本\n- 列表语法也只是字符",
                "最后一段",
            ),
            result.map(RawMemoParagraph::text),
        )
    }

    @Test
    fun `raw memo paragraph splitter drops whitespace only gaps without inventing markdown structure`() {
        val result =
            splitRawMemoParagraphs(
                "alpha\n \n\t\n> quote marker stays literal\n\n  \nplain tail",
            )

        assertEquals(
            listOf(
                "alpha",
                "> quote marker stays literal",
                "plain tail",
            ),
            result.map(RawMemoParagraph::text),
        )
    }

    @Test
    fun `shared plain text memo style keeps editor and rendered memo body visually identical`() {
        val text = "今天 review memo"

        val resolved = resolveRawMemoPlainTextStyle(typography = typography, text = text)

        assertEquals(typography.memoBodyTextStyle().fontSize, resolved.fontSize)
        assertEquals(typography.memoBodyTextStyle().lineHeight, resolved.lineHeight)
        assertEquals(typography.memoBodyTextStyle().letterSpacing, resolved.letterSpacing)
        assertEquals(PlatformTextStyle(includeFontPadding = false), resolved.platformStyle)
        assertEquals(typography.memoEditorTextStyle().scriptAwareFor(text), resolved)
    }

    @Test
    fun `shared raw memo paragraph spacing matches rendered memo block rhythm`() {
        assertEquals(8.dp, rawMemoParagraphSpacing())
    }
}
