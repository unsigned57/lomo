package com.lomo.ui.component.input

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo input cursor fallback sizing policy.
 * - Behavior focus: the fallback cursor drawable must stay visibly large enough after the new EditText rendering path reapplies paragraph styling.
 * - Observable outcomes: non-trivial width and height for the generated fallback cursor size.
 * - Red phase: Fails before the fix because the fallback cursor height collapses to a near-invisible 1px line even when the editor has a normal text size.
 * - Excludes: Android widget attachment, IME blink timing, and OEM cursor artwork.
 */
class MemoInputCursorDrawablePolicyTest {
    @Test
    fun `cursor styling uses reflection below android q and drawable property on q plus`() {
        assertEquals(MemoInputCursorStylingStrategy.Reflection, resolveMemoInputCursorStylingStrategy(28))
        assertEquals(MemoInputCursorStylingStrategy.DrawableProperty, resolveMemoInputCursorStylingStrategy(29))
    }

    @Test
    fun `fallback cursor uses visible width and text-sized height`() {
        val size =
            resolveFallbackCursorDrawableSize(
                density = 1f,
                textSizePx = 16f,
            )

        assertTrue(size.widthPx >= 2)
        assertTrue(size.heightPx >= 16)
    }

    @Test
    fun `fallback cursor height keeps a visible minimum for tiny text sizes`() {
        val size =
            resolveFallbackCursorDrawableSize(
                density = 2f,
                textSizePx = 8f,
            )

        assertTrue(size.widthPx >= 4)
        assertTrue(size.heightPx >= 36)
    }
}
