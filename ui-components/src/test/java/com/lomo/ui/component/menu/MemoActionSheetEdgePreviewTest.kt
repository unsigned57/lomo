package com.lomo.ui.component.menu

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: predictive menu edge preview helpers in MemoActionSheet
 * - Behavior focus: keep the retired predictive edge preview path fully disabled so menu boundary feedback comes only from Compose overscroll.
 * - Observable outcomes: zero preview offsets for near-edge drags, near-edge flings, and retained-preview inputs.
 * - Red phase: Fails before the fix because the predictive helpers still emit non-zero offsets near the horizontal boundaries and can retain a previous preview.
 * - Excludes: Compose overscroll rendering, indicator thumb dragging, and bottom-sheet host wiring.
 */
class MemoActionSheetEdgePreviewTest {
    @Test
    fun `calculateMenuEdgePreviewOffset stays idle near the start edge`() {
        val preview =
            calculateMenuEdgePreviewOffset(
                dragDeltaX = 120f,
                scrollValue = 72,
                maxScroll = 900,
                viewportWidthPx = 320,
            )

        assertEquals(0f, preview, 0.0001f)
    }

    @Test
    fun `calculateMenuEdgePreviewOffset stays idle when the drag is far from both edges`() {
        val preview =
            calculateMenuEdgePreviewOffset(
                dragDeltaX = -120f,
                scrollValue = 320,
                maxScroll = 1000,
                viewportWidthPx = 240,
            )

        assertEquals(0f, preview, 0.0001f)
    }

    @Test
    fun `calculateMenuEdgePreviewFlingOffset stays idle near the end edge`() {
        val preview =
            calculateMenuEdgePreviewFlingOffset(
                velocityX = -2400f,
                scrollValue = 760,
                maxScroll = 900,
                viewportWidthPx = 240,
            )

        assertEquals(0f, preview, 0.0001f)
    }

    @Test
    fun `calculateMenuEdgePreviewFlingOffset stays idle for a strong fling from mid list`() {
        val preview =
            calculateMenuEdgePreviewFlingOffset(
                velocityX = -5200f,
                scrollValue = 420,
                maxScroll = 900,
                viewportWidthPx = 240,
            )

        assertEquals(0f, preview, 0.0001f)
    }

    @Test
    fun `calculateMenuEdgePreviewFlingOffset ignores retained preview state`() {
        val retainedPreview =
            calculateMenuEdgePreviewFlingOffset(
                velocityX = -2400f,
                scrollValue = 500,
                maxScroll = 900,
                viewportWidthPx = 240,
                retainedPreviewOffsetPx = -8f,
            )

        assertEquals(0f, retainedPreview, 0.0001f)
    }
}
