package com.lomo.ui.theme

/*
 * Behavior Contract:
 * Test Change Justification:
 * Reason category: User-approved behavior change.
 * Old behavior/assertion being replaced: compact memo defaults of 14sp with 16/20sp line heights.
 * Why old assertion is no longer correct: the approved Expressive reading baseline is 16sp/24sp.
 * Coverage preserved by: body/editor/summary/hint parity, scaling, CJK alignment and spacing assertions.
 * Why this is not fitting the test to the implementation: the new defaults were fixed in the approved plan.
 * - Unit under test: MemoTypography memo text tokens
 * - Capability: memo reading typography keeps one Expressive baseline across body, editor, hint
 *   and summary styles, with CJK glyphs aligned proportionally.
 * - Scenarios:
 *   1. Given memo body/editor/hint/summary styles, when resolved, then they keep the tightened
 *      reading spacing and the compact memo line-height rhythm.
 *   2. Given CJK glyphs in editor or hint styles, when rendered, then line-height aligns
 *      proportionally and Android font padding is disabled so CJK characters share the same
 *      in-line balance as Latin.
 * - Observable outcomes: TextStyle.fontSize, lineHeight, letterSpacing, lineHeightStyle, platformStyle
 *   and memoParagraphBlockSpacing.
 * - TDD proof: Fails before the CJK alignment fix because lineHeightStyle on memoEditorTextStyle/memoHintTextStyle is Center instead of Proportional.
 * - Excludes: MaterialTheme composition wiring, OEM font rendering differences, BasicTextField layout.
 */

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.Typography as MaterialTypography

class MemoTypographyTest : UiComponentsFunSpec() {
    init {
        val typography = MaterialTypography()
        val appTypography = buildAppTypography(FontFamily.SansSerif)
        val defaultScales = TypographyScales()
        val cjkProportionalLineHeight =
            LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Proportional,
                trim = LineHeightStyle.Trim.None,
            )
        val cjkPlatformStyle = PlatformTextStyle(includeFontPadding = false)

        test("material body medium follows the current Material type scale") {
            (appTypography.bodyMedium.fontSize) shouldBe (14.sp)
            (appTypography.bodyMedium.lineHeight) shouldBe (20.sp)
            (appTypography.bodyMedium.letterSpacing) shouldBe (0.2.sp)
        }

        test("memo body and editor share the Expressive reading metrics") {
            val body = typography.memoBodyTextStyle(defaultScales)
            val editor = typography.memoEditorTextStyle(defaultScales)
            val hint = typography.memoHintTextStyle(defaultScales)

            (body.fontSize) shouldBe (16.sp)
            (body.lineHeight) shouldBe (24.sp)
            (body.letterSpacing) shouldBe (0.1.sp)

            (editor.fontSize) shouldBe (body.fontSize)
            (editor.lineHeight) shouldBe (body.lineHeight)
            (editor.letterSpacing) shouldBe (body.letterSpacing)

            (hint.fontSize) shouldBe (body.fontSize)
            (hint.lineHeight) shouldBe (body.lineHeight)
            (hint.letterSpacing) shouldBe (body.letterSpacing)
        }

        test("memo summary shares the Expressive reading rhythm") {
            val summary = typography.memoSummaryTextStyle(defaultScales)

            (summary.fontSize) shouldBe (16.sp)
            (summary.lineHeight) shouldBe (24.sp)
            (summary.letterSpacing) shouldBe (0.1.sp)
        }

        test("memo paragraph block spacing stays clearly larger than the compact line rhythm") {
            (memoParagraphBlockSpacing(defaultScales)) shouldBe (8.dp)
        }

        test("memo editor style aligns CJK glyphs proportionally and disables Android font padding") {
            val editor = appTypography.memoEditorTextStyle(defaultScales)

            (editor.lineHeightStyle) shouldBe (cjkProportionalLineHeight)
            (editor.platformStyle) shouldBe (cjkPlatformStyle)
        }

        test("memo hint style aligns CJK glyphs proportionally and disables Android font padding") {
            val hint = appTypography.memoHintTextStyle(defaultScales)

            (hint.lineHeightStyle) shouldBe (cjkProportionalLineHeight)
            (hint.platformStyle) shouldBe (cjkPlatformStyle)
        }

        test("memo body and list styles in the app typography do not opt into CJK centering") {
            val body = appTypography.memoBodyTextStyle(defaultScales)
            val list = appTypography.memoListTextStyle(defaultScales)

            (body.lineHeightStyle) shouldBe (null)
            (list.lineHeightStyle) shouldBe (null)
        }
    }
}
