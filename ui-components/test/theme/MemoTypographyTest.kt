package com.lomo.ui.theme

/*
 * Behavior Contract:
 * - Unit under test: MemoTypography memo text tokens
 * - Behavior focus:
 *   1. Global body-medium and memo body/editor/hint/summary styles keep the tightened reading spacing
 *      while preserving the compact memo line-height rhythm.
 *   2. Editor and hint styles use a line-height that aligns CJK glyphs proportionally and disables
 *      Android's font padding so CJK characters render with the same comfortable in-line balance as
 *      Latin instead of clinging to the bottom of the line box.
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

        test("material body medium keeps the tightened global reading spacing") {
            (appTypography.bodyMedium.fontSize) shouldBe (14.sp)
            (appTypography.bodyMedium.lineHeight) shouldBe (20.sp)
            (appTypography.bodyMedium.letterSpacing) shouldBe (0.1.sp)
        }

        test("memo body and editor styles stay close to the previous body medium size") {
            val body = typography.memoBodyTextStyle(defaultScales)
            val editor = typography.memoEditorTextStyle(defaultScales)
            val hint = typography.memoHintTextStyle(defaultScales)

            (body.fontSize) shouldBe (14.sp)
            (body.lineHeight) shouldBe (16.sp)
            (body.letterSpacing) shouldBe (0.1.sp)

            (editor.fontSize) shouldBe (body.fontSize)
            (editor.lineHeight) shouldBe (body.lineHeight)
            (editor.letterSpacing) shouldBe (body.letterSpacing)

            (hint.fontSize) shouldBe (body.fontSize)
            (hint.lineHeight) shouldBe (body.lineHeight)
            (hint.letterSpacing) shouldBe (body.letterSpacing)
        }

        test("memo summary keeps compact body medium rhythm") {
            val summary = typography.memoSummaryTextStyle(defaultScales)

            (summary.fontSize) shouldBe (14.sp)
            (summary.lineHeight) shouldBe (20.sp)
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
