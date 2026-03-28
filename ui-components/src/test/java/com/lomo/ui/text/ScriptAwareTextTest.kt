package com.lomo.ui.text

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import android.text.Layout
import com.lomo.ui.component.markdown.MarkdownParser
import com.lomo.ui.component.markdown.appendNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: ScriptAwareText memo paragraph layout policy and mixed-script formatter contract.
 * - Behavior focus: mixed CJK/Latin spacing, Chinese punctuation conversion, paragraph alignment policy for pure-CJK vs mixed prose, rich-text boundary normalization, memo paragraph renderer policy for plain paragraphs vs rich text, compose text metric alignment for rich mixed prose, and platform inter-character justification selection for share-card style plain Chinese long-form text.
 * - Observable outcomes: transformed display strings, selected text alignment, selected line-break strategy, selected Android justification mode, selected memo paragraph renderer path, resolved compose platform text style, and styled span ranges after markdown inline rendering.
 * - Red phase: Fails before the fix because plain mixed prose stays on Compose Text while plain pure-Chinese paragraphs use TextView, so memo paragraph rhythm diverges across plain paragraph types.
 * - Excludes: Compose widget rendering, OEM font rasterization differences, TextView measurement internals, and markdown block layout containers.
 */
class ScriptAwareTextTest {
    @Test
    fun `normalize display text keeps markdown markers and technical tokens while spacing mixed prose`() {
        val input = "- [ ] 今天review C# 版本v1.2 https://a.com"

        val result = input.normalizeCjkMixedSpacingForDisplay()

        assertEquals("- [ ] 今天 review C# 版本 v1.2 https://a.com", result)
    }

    @Test
    fun `normalize display text covers slash in chinese prose but preserves urls`() {
        val input = "接口: 输入/输出 https://a.com/a/b"

        val result = input.normalizeCjkMixedSpacingForDisplay()

        assertEquals("接口： 输入／输出 https://a.com/a/b", result)
    }

    @Test
    fun `normalize display text converts paired quotes parentheses and dash runs in chinese prose`() {
        val input = "她说\"hello\"，然后写下'world'(test)----完成"

        val result = input.normalizeCjkMixedSpacingForDisplay()

        assertEquals("她说“hello”，然后写下‘world’（test）————完成", result)
    }

    @Test
    fun `normalize display text keeps apostrophes and technical parentheses in latin tokens`() {
        val input = "今天 review it's foo(bar)"

        val result = input.normalizeCjkMixedSpacingForDisplay()

        assertEquals("今天 review it's foo(bar)", result)
    }

    @Test
    fun `normalize display text collapses repeated ascii quotes around chinese text`() {
        val input = "她写下\"\"中文\"\"，又写下''中文''"

        val result = input.normalizeCjkMixedSpacingForDisplay()

        assertEquals("她写下“中文”，又写下‘中文’", result)
    }

    @Test
    fun `script aware alignment no longer forces justify for mixed cjk paragraphs`() {
        assertEquals(TextAlign.Start, "今天 review".scriptAwareTextAlign())
    }

    @Test
    fun `script aware alignment keeps justify for chinese long prose with quotes and colon`() {
        val normalized =
            "这是一段很长的\"中文引号\"段落内容：应该保持两端对齐。"
                .normalizeCjkMixedSpacingForDisplay()

        assertEquals(TextAlign.Justify, normalized.scriptAwareTextAlign())
    }

    @Test
    fun `script aware style keeps default paragraph line breaking for mixed cjk paragraphs`() {
        val style = androidx.compose.ui.text.TextStyle().scriptAwareFor("今天 review 很长的一段 mixed text")

        assertEquals(LineBreak.Paragraph, style.lineBreak)
    }

    @Test
    fun `script aware style disables font padding for mixed cjk paragraphs`() {
        val style = androidx.compose.ui.text.TextStyle().scriptAwareFor("今天 review 很长的一段 mixed text")

        assertEquals(PlatformTextStyle(includeFontPadding = false), style.platformStyle)
    }

    @Test
    fun `script aware style centers mixed cjk line height without trimming paragraph edges`() {
        val style = androidx.compose.ui.text.TextStyle().scriptAwareFor("今天 review 很长的一段 mixed text")

        assertEquals(
            LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
            style.lineHeightStyle,
        )
    }

    @Test
    fun `script aware style keeps paragraph line breaking for chinese long prose with quotes and colon`() {
        val style =
            androidx.compose.ui.text.TextStyle().scriptAwareFor(
                "这是一段很长的“中文引号”段落内容：应该保持两端对齐。",
            )

        assertEquals(LineBreak.Paragraph, style.lineBreak)
    }

    @Test
    fun `script aware style does not enable proportional cjk punctuation compaction for chinese prose with quotes and colon`() {
        val style =
            androidx.compose.ui.text.TextStyle().scriptAwareFor(
                "这是一段很长的“中文引号”段落内容：应该保持两端对齐。",
            )

        assertEquals(null, style.fontFeatureSettings)
    }

    @Test
    fun `platform cjk justification uses inter-character for the anonymized pure chinese paragraph`() {
        val text = ANONYMIZED_PURE_CJK_PARAGRAPH

        assertTrue(text.shouldUsePlatformCjkJustification())
        assertEquals(Layout.JUSTIFICATION_MODE_INTER_CHARACTER, text.platformJustificationMode())
    }

    @Test
    fun `memo paragraph renderer keeps platform path for long pure chinese paragraph`() {
        val text = AnnotatedString(ANONYMIZED_PURE_CJK_PARAGRAPH)

        assertTrue(text.shouldUsePlatformMemoParagraphRendering())
    }

    @Test
    fun `memo paragraph renderer keeps platform path for short pure chinese paragraph`() {
        val text = AnnotatedString("这是纯中文短段落，用来确认仍然保持原生两端对齐。")

        assertTrue(text.shouldUsePlatformMemoParagraphRendering())
        assertEquals(Layout.JUSTIFICATION_MODE_INTER_CHARACTER, text.text.platformJustificationMode())
    }

    @Test
    fun `memo paragraph renderer keeps platform path for mixed chinese english prose`() {
        val text = AnnotatedString(ANONYMIZED_COMPLEX_MIXED_PROSE)

        assertTrue(text.shouldUsePlatformMemoParagraphRendering())
    }

    @Test
    fun `memo paragraph renderer stays on compose path when annotated text carries span styles`() {
        val text =
            buildAnnotatedString {
                append("王夫之，用")
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                append("体用")
                pop()
                append("讨论主客体关系")
            }

        assertFalse(text.shouldUsePlatformMemoParagraphRendering())
    }

    @Test
    fun `appendNode inserts mixed-script spacing across emphasis boundaries`() {
        val paragraph = MarkdownParser.parse("今天**bold**示例").node.firstChild

        val result =
            buildAnnotatedString {
                appendNode(checkNotNull(paragraph), lightColorScheme())
            }

        assertEquals("今天 bold 示例", result.text)
        val boldRange = result.spanStyles.single()
        assertEquals(FontWeight.Bold, boldRange.item.fontWeight)
        assertEquals(3, boldRange.start)
        assertEquals(7, boldRange.end)
    }

    @Test
    fun `appendNode preserves inline code literal without punctuation rewriting`() {
        val paragraph = MarkdownParser.parse("今天`C#`示例").node.firstChild

        val result =
            buildAnnotatedString {
                appendNode(checkNotNull(paragraph), lightColorScheme())
            }

        assertEquals("今天C#示例", result.text)
        val codeRange = result.spanStyles.single()
        assertTrue(codeRange.item.fontFamily != null)
    }

    companion object {
        private const val ANONYMIZED_PURE_CJK_PARAGRAPH =
            "某位研究者在笔记中写道：“当系统讨论‘主体’与‘对象’的关系时，既不能把对象看成空无，也不能把作用理解成幻影；若说某种条件有待触发，则必先承认其自身存在；若说某种能力能够生效，则也必须承认其现实作用。进一步看，条件引出能力，能力又必须符合条件；若只剩下主观判断，而把对象完全吞没，那么结论看似完整，实际却会偏离事实。”随后，他又补充：这样的分析并不是为了制造抽象术语，而是为了提醒读者，在处理记录、解释、引用、转述与反驳时，都要让“所见”与“所言”彼此对应。"

        private const val ANONYMIZED_COMPLEX_MIXED_PROSE =
            "这段 memo 会同时出现 Module-A/v2.7、JSON/XML、\"quoted text\"、'single quote'、(alpha+beta)、C:\\demo\\notes、https://demo.example.org/a/b?id=42，以及 README.md、`SELECT * FROM log_table;` 和 email:test@example.org，用来确认 mixed prose 不会误切到原生中文两端对齐。"
    }
}
