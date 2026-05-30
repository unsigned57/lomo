package com.lomo.ui.text

/**
 * Behavior Contract:
 * Capability: Script aware text layout metrics
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure or assertion failure on proportional alignment
 * Excludes: none
 */

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import android.text.Layout
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign

class ScriptAwareTextTest : UiComponentsFunSpec() {
    init {
        test("normalize display text keeps markdown markers and technical tokens while spacing mixed prose") {
            val input = "- [ ] 今天review C# 版本v1.2 https://a.com"
            val result = input.normalizeCjkMixedSpacingForDisplay()
            (result) shouldBe ("- [ ] 今天 review C# 版本 v1.2 https://a.com")
        }

        test("normalize display text covers slash in chinese prose but preserves urls") {
            val input = "接口: 输入/输出 https://a.com/a/b"
            val result = input.normalizeCjkMixedSpacingForDisplay()
            (result) shouldBe ("接口： 输入／输出 https://a.com/a/b")
        }

        test("normalize display text converts paired quotes parentheses and dash runs in chinese prose") {
            val input = "她说\"hello\"，然后写下'world'(test)----完成"
            val result = input.normalizeCjkMixedSpacingForDisplay()
            (result) shouldBe ("她说“hello”，然后写下‘world’（test）————完成")
        }

        test("normalize display text keeps apostrophes and technical parentheses in latin tokens") {
            val input = "今天 review it's foo(bar)"
            val result = input.normalizeCjkMixedSpacingForDisplay()
            (result) shouldBe ("今天 review it's foo(bar)")
        }

        test("normalize display text collapses repeated ascii quotes around chinese text") {
            val input = "她写下\"\"中文\"\"，又写下''中文''"
            val result = input.normalizeCjkMixedSpacingForDisplay()
            (result) shouldBe ("她写下“中文”，又写下‘中文’")
        }

        test("script aware alignment no longer forces justify for mixed cjk paragraphs") {
            ("今天 review".scriptAwareTextAlign()) shouldBe (TextAlign.Start)
        }

        test("script aware alignment keeps justify for chinese long prose with quotes and colon") {
            val normalized =
                "这是一段很长的\"中文引号\"段落内容：应该保持两端对齐。"
                    .normalizeCjkMixedSpacingForDisplay()
            (normalized.scriptAwareTextAlign()) shouldBe (TextAlign.Justify)
        }

        test("script aware style keeps default paragraph line breaking for mixed cjk paragraphs") {
            val style = androidx.compose.ui.text.TextStyle().scriptAwareFor("今天 review 很长的一段 mixed text")
            (style.lineBreak) shouldBe (LineBreak.Paragraph)
        }

        test("script aware style disables font padding for mixed cjk paragraphs") {
            val style = androidx.compose.ui.text.TextStyle().scriptAwareFor("今天 review 很长的一段 mixed text")
            (style.platformStyle) shouldBe (PlatformTextStyle(includeFontPadding = false))
        }

        test("script aware style uses proportional mixed cjk line height without trimming paragraph edges") {
            val style = androidx.compose.ui.text.TextStyle().scriptAwareFor("今天 review 很长的一段 mixed text")
            (style.lineHeightStyle) shouldBe (LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Proportional,
                trim = LineHeightStyle.Trim.None,
            ))
        }

        test("script aware style keeps paragraph line breaking for chinese long prose with quotes and colon") {
            val style =
                androidx.compose.ui.text.TextStyle().scriptAwareFor(
                    "这是一段很长的“中文引号”段落内容：应该保持两端对齐。",
                )
            (style.lineBreak) shouldBe (LineBreak.Paragraph)
        }

        test("script aware style does not enable proportional cjk punctuation compaction for chinese prose with quotes and colon") {
            val style =
                androidx.compose.ui.text.TextStyle().scriptAwareFor(
                    "这是一段很长的“中文引号”段落内容：应该保持两端对齐。",
                )
            (style.fontFeatureSettings) shouldBe (null)
        }

        test("platform cjk justification uses inter-character for the anonymized pure chinese paragraph") {
            val text = ANONYMIZED_PURE_CJK_PARAGRAPH
            (text.shouldUsePlatformCjkJustification()) shouldBe true
            (text.platformJustificationMode()) shouldBe (Layout.JUSTIFICATION_MODE_INTER_CHARACTER)
        }

        test("short pure chinese paragraph keeps platform justification policy available to share cards") {
            val text = "这是纯中文短段落，用来确认仍然保持原生两端对齐。"
            (text.platformJustificationMode()) shouldBe (Layout.JUSTIFICATION_MODE_INTER_CHARACTER)
        }
    }

    companion object {
        private const val ANONYMIZED_PURE_CJK_PARAGRAPH =
            "某位研究者在笔记中写道：“当系统讨论‘主体’与‘对象’的关系时，既不能把对象看成空无，也不能把作用理解成幻影；若说某种条件有待触发，则必先承认其自身存在；若说某种能力能够生效，则也必须承认其现实作用。进一步看，条件引出能力，能力又必须符合条件；若只剩下主观判断，而把对象完全吞没，那么结论看似完整，实际却会偏离事实。”随后，他又补充：这样的分析并不是为了制造抽象术语，而是为了提醒读者，在处理记录、解释、引用、转述与反驳时，都要让“所见”与“所言”彼此对应。"
    }
}
