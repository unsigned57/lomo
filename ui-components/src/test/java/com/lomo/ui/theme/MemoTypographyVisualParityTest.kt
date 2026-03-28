package com.lomo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo typography visual-parity tokens.
 * - Behavior focus: editor-side Compose text tokens must opt out of platform font padding so the input panel matches the tighter TextView-backed memo card rhythm.
 * - Observable outcomes: resolved platform text style for memo body, editor, summary, and hint tokens.
 * - Red phase: Fails before the fix because memo typography leaves Compose-side platform font padding at the default, making input-panel text metrics drift from memo card rendering.
 * - Excludes: actual TextField layout, OEM font metrics, and card/editor container padding.
 */
class MemoTypographyVisualParityTest {
    private val typography = Typography()

    @Test
    fun `memo typography tokens disable platform font padding for card and editor parity`() {
        val expectedPlatformStyle = PlatformTextStyle(includeFontPadding = false)

        assertEquals(expectedPlatformStyle, typography.memoBodyTextStyle().platformStyle)
        assertEquals(expectedPlatformStyle, typography.memoEditorTextStyle().platformStyle)
        assertEquals(expectedPlatformStyle, typography.memoSummaryTextStyle().platformStyle)
        assertEquals(expectedPlatformStyle, typography.memoHintTextStyle().platformStyle)
    }
}
