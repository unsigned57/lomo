package com.lomo.ui.text

/*
 * Behavior Contract:
 * Capability: raw memo plain-text presentation spec for editor/display parity.
 * Scenarios: Given raw memo text and default or scaled typography preferences, when resolving
 * paragraphs, text style, and paragraph spacing, then editor and rendered body share identical
 * metrics and 6dp default paragraph rhythm.
 * Observable outcomes: split raw paragraphs, preserved markdown marker literals, resolved shared
 * text style, and 6dp shared paragraph spacing.
 * TDD proof: `./kotlin test --include-module=ui-components` fails when rawMemoParagraphSpacing
 * disagrees with memoParagraphBlockSpacing (6.dp).
 * Excludes: Compose widget-tree rendering, TextView/EditText measurement internals, IME integration.
 *
 * Test Change Justification:
 * Reason category: M3 Expressive memo card typographic hierarchy refinement.
 * Old behavior/assertion being replaced: rawMemoParagraphSpacing(defaultScales) == 8.dp.
 * Why old assertion is no longer correct: default memoParagraphBlockSpacing was refined from 8.dp
 * to 6.dp to balance the 15sp/22sp M3E reading rhythm.
 * Coverage preserved by: asserting rawMemoParagraphSpacing(defaultScales) == 6.dp.
 * Why this is not fitting the test to the implementation: verifies the shared paragraph spacing
 * token matches the updated 6.dp M3E design specification.
 */

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lomo.ui.testing.UiComponentsFunSpec
import com.lomo.ui.theme.TypographyScales
import com.lomo.ui.theme.memoBodyTextStyle
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: raw memo plain-text presentation spec for editor/display parity.
 * - Behavior focus: editor-side raw text and rendered memo body text must resolve through the same paragraph splitter, spacing token, and script-aware body style so plain prose looks identical without interpreting Markdown syntax.
 * - Observable outcomes: split raw paragraphs, preserved markdown marker literals, resolved shared text style, and shared paragraph spacing.
 * - TDD proof: Fails before the fix because the project has no shared raw memo presentation spec, so editor text and rendered memo body text cannot be proven to share the same paragraph policy.
 * - Excludes: Compose widget-tree rendering, TextView/EditText measurement internals, IME integration, and markdown semantic parsing.
 */
class MemoPlainTextPresentationTest : UiComponentsFunSpec() {
    private val typography = Typography()
    private val defaultScales = TypographyScales()

    init {
        test("raw memo paragraph splitter keeps intra paragraph line breaks and splits only on blank lines") {
            val result =
                splitRawMemoParagraphs(
                    "第一行\n第二行\n\n# 标题语法仍是普通文本\n- 列表语法也只是字符\n\n最后一段",
                )

            (result.map(RawMemoParagraph::text)) shouldBe (
                listOf(
                    "第一行\n第二行",
                    "# 标题语法仍是普通文本\n- 列表语法也只是字符",
                    "最后一段",
                )
                )
        }
    }

    init {
        test("raw memo paragraph splitter drops whitespace only gaps without inventing markdown structure") {
            val result =
                splitRawMemoParagraphs(
                    "alpha\n \n\t\n> quote marker stays literal\n\n  \nplain tail",
                )

            (result.map(RawMemoParagraph::text)) shouldBe (
                listOf(
                    "alpha",
                    "> quote marker stays literal",
                    "plain tail",
                )
                )
        }
    }

    init {
        test("shared plain text memo style keeps editor and rendered memo body visually identical") {
            val text = "今天 review memo"

            val resolved = resolveRawMemoPlainTextStyle(typography = typography, text = text, scales = defaultScales)

            (resolved.fontSize) shouldBe (typography.memoBodyTextStyle(defaultScales).fontSize)
            (resolved.lineHeight) shouldBe (typography.memoBodyTextStyle(defaultScales).lineHeight)
            (resolved.letterSpacing) shouldBe (typography.memoBodyTextStyle(defaultScales).letterSpacing)
            (resolved.platformStyle) shouldBe (PlatformTextStyle(includeFontPadding = false))
            (resolved) shouldBe (typography.memoBodyTextStyle(defaultScales).scriptAwareFor(text))
        }
    }

    init {
        test("shared plain text memo style preserves configured letter spacing for cjk mixed text") {
            val text = "今天 review memo"
            val adjustedScales = TypographyScales(letterSpacingScale = 1.5f)

            val resolved = resolveRawMemoPlainTextStyle(typography = typography, text = text, scales = adjustedScales)

            (typography.memoBodyTextStyle(adjustedScales).letterSpacing) shouldBe (0.6.sp)
            (resolved.letterSpacing) shouldBe (typography.memoBodyTextStyle(adjustedScales).letterSpacing)
            (resolved.platformStyle) shouldBe (PlatformTextStyle(includeFontPadding = false))
        }
    }

    init {
        test("shared raw memo paragraph spacing matches rendered memo block rhythm") {
            (rawMemoParagraphSpacing(defaultScales)) shouldBe (6.dp)
        }
    }
}
