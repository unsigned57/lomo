package com.lomo.ui.theme

import androidx.compose.material3.Typography as MaterialTypography
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoTypography memo text tokens
 * - Behavior focus: global body-medium text and memo body, editor, hint, and summary styles keep the tightened reading spacing while preserving the compact memo line-height rhythm.
 * - Observable outcomes: selected fontSize, lineHeight, letterSpacing, and memo paragraph block spacing values.
 * - Red phase: Fails before the fix because bodyMedium still uses 0.25sp and memo text tokens still use 0sp instead of the tightened 0.1sp spacing contract.
 * - Excludes: MaterialTheme composition wiring, OEM font rendering differences, and downstream Text composable layout.
 */
class MemoTypographyTest {
    private val typography = MaterialTypography()
    private val appTypography = Typography

    @Test
    fun `material body medium keeps the tightened global reading spacing`() {
        assertEquals(14.sp, appTypography.bodyMedium.fontSize)
        assertEquals(20.sp, appTypography.bodyMedium.lineHeight)
        assertEquals(0.1.sp, appTypography.bodyMedium.letterSpacing)
    }

    @Test
    fun `memo body and editor styles stay close to the previous body medium size`() {
        val body = typography.memoBodyTextStyle()
        val editor = typography.memoEditorTextStyle()
        val hint = typography.memoHintTextStyle()

        assertEquals(14.sp, body.fontSize)
        assertEquals(16.sp, body.lineHeight)
        assertEquals(0.1.sp, body.letterSpacing)

        assertEquals(body.fontSize, editor.fontSize)
        assertEquals(body.lineHeight, editor.lineHeight)
        assertEquals(body.letterSpacing, editor.letterSpacing)
        assertEquals(body, editor)

        assertEquals(body.fontSize, hint.fontSize)
        assertEquals(body.lineHeight, hint.lineHeight)
        assertEquals(body.letterSpacing, hint.letterSpacing)
    }

    @Test
    fun `memo summary keeps compact body medium rhythm`() {
        val summary = typography.memoSummaryTextStyle()

        assertEquals(14.sp, summary.fontSize)
        assertEquals(20.sp, summary.lineHeight)
        assertEquals(0.1.sp, summary.letterSpacing)
    }

    @Test
    fun `memo paragraph block spacing stays clearly larger than the compact line rhythm`() {
        assertEquals(8.dp, memoParagraphBlockSpacing())
    }
}
