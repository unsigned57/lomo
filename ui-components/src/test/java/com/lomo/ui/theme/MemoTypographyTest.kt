package com.lomo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoTypography memo text tokens
 * - Behavior focus: memo body, editor, and hint styles expose the requested 16sp line height, and editor text stays token-identical to memo body so input panels do not drift from memo content.
 * - Observable outcomes: selected fontSize, lineHeight, letterSpacing, and memo paragraph block spacing values.
 * - Red phase: Fails before the fix because memo body, editor, and hint still keep a 17sp line height, not the requested 16sp rhythm.
 * - Excludes: MaterialTheme composition wiring, OEM font rendering differences, and downstream Text composable layout.
 */
class MemoTypographyTest {
    private val typography = Typography()

    @Test
    fun `memo body and editor styles stay close to the previous body medium size`() {
        val body = typography.memoBodyTextStyle()
        val editor = typography.memoEditorTextStyle()
        val hint = typography.memoHintTextStyle()

        assertEquals(14.sp, body.fontSize)
        assertEquals(16.sp, body.lineHeight)
        assertEquals(0.sp, body.letterSpacing)

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
        assertEquals(0.sp, summary.letterSpacing)
    }

    @Test
    fun `memo paragraph block spacing stays clearly larger than the compact line rhythm`() {
        assertEquals(8.dp, memoParagraphBlockSpacing())
    }
}
