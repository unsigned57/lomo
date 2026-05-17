package com.lomo.ui.theme

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.Typography as MaterialTypography
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Test Contract:
 * - Unit under test: MemoTypography memo text tokens
 * - Behavior focus: global body-medium text and memo body, editor, hint, and summary styles keep the tightened reading spacing while preserving the compact memo line-height rhythm.
 * - Observable outcomes: selected fontSize, lineHeight, letterSpacing, and memo paragraph block spacing values.
 * - Red phase: Fails before the fix because bodyMedium still uses 0.25sp and memo text tokens still use 0sp instead of the tightened 0.1sp spacing contract.
 * - Excludes: MaterialTheme composition wiring, OEM font rendering differences, and downstream Text composable layout.
 */
class MemoTypographyTest : UiComponentsFunSpec() {
    private val typography = MaterialTypography()
    private val appTypography = Typography
    private val defaultScales = TypographyScales()

    init {
        test("material body medium keeps the tightened global reading spacing") {
        (appTypography.bodyMedium.fontSize) shouldBe (14.sp)
        (appTypography.bodyMedium.lineHeight) shouldBe (20.sp)
        (appTypography.bodyMedium.letterSpacing) shouldBe (0.1.sp)
        }
    }

    init {
        test("memo body and editor styles stay close to the previous body medium size") {
        val body = typography.memoBodyTextStyle(defaultScales)
        val editor = typography.memoBodyTextStyle(defaultScales) // memoEditorTextStyle delegates to memoBodyTextStyle
        val hint = typography.memoHintTextStyle(defaultScales)

        (body.fontSize) shouldBe (14.sp)
        (body.lineHeight) shouldBe (16.sp)
        (body.letterSpacing) shouldBe (0.1.sp)

        (editor.fontSize) shouldBe (body.fontSize)
        (editor.lineHeight) shouldBe (body.lineHeight)
        (editor.letterSpacing) shouldBe (body.letterSpacing)
        (editor) shouldBe (body)

        (hint.fontSize) shouldBe (body.fontSize)
        (hint.lineHeight) shouldBe (body.lineHeight)
        (hint.letterSpacing) shouldBe (body.letterSpacing)
        }
    }

    init {
        test("memo summary keeps compact body medium rhythm") {
        val summary = typography.memoSummaryTextStyle(defaultScales)

        (summary.fontSize) shouldBe (14.sp)
        (summary.lineHeight) shouldBe (20.sp)
        (summary.letterSpacing) shouldBe (0.1.sp)
        }
    }

    init {
        test("memo paragraph block spacing stays clearly larger than the compact line rhythm") {
        (memoParagraphBlockSpacing(defaultScales)) shouldBe (8.dp)
        }
    }
}
