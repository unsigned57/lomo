package com.lomo.ui.component.input

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo input minimum-height policy.
 * - Behavior focus: the empty editor and first-character editor must share the same minimum content height so the input container does not jump when text becomes non-empty.
 * - Observable outcomes: deterministic minimum-height pixel calculations from the editor text style.
 * - Red phase: Fails before the fix because the input editor has no shared minimum-height policy, so empty and non-empty states can measure from different widget paths.
 * - Excludes: IME animation, Compose sheet transitions, and OEM EditText rendering quirks.
 */
class MemoInputHeightPolicyTest {
    @Test
    fun `minimum content height uses explicit line height across the configured minimum lines`() {
        val minHeight =
            resolveMemoInputMinimumContentHeightPx(
                style = TextStyle(fontSize = 14.sp, lineHeight = 16.sp),
                density = Density(density = 1f),
            )

        assertEquals(48, minHeight)
    }

    @Test
    fun `minimum content height falls back to font size when line height is unspecified`() {
        val minHeight =
            resolveMemoInputMinimumContentHeightPx(
                style = TextStyle(fontSize = 18.sp),
                density = Density(density = 1f),
            )

        assertEquals(54, minHeight)
    }
}
