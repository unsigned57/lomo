package com.lomo.ui.component.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: menu edge preview helpers in MemoActionSheet
 * - Behavior focus: trigger anticipatory edge preview before the content actually collides with the horizontal boundary.
 * - Observable outcomes: signed preview offsets for near-edge drags and flings, plus zero offset when motion is far from any boundary.
 * - Red phase: Fails before the fix because pre-fling motion near the boundary has no anticipatory preview helper and only the post-collision overscroll effect appears.
 * - Excludes: Compose rendering, indicator thumb dragging, and bottom-sheet host wiring.
 */
class MemoActionSheetEdgePreviewTest {
    @Test
    fun `calculateMenuEdgePreviewOffset responds before reaching the start edge`() {
        val preview =
            calculateMenuEdgePreviewOffset(
                dragDeltaX = 120f,
                scrollValue = 72,
                maxScroll = 900,
                viewportWidthPx = 320,
            )

        assertTrue(preview > 0f)
    }

    @Test
    fun `calculateMenuEdgePreviewOffset stays idle when the fling is far from both edges`() {
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
    fun `calculateMenuEdgePreviewFlingOffset anticipates the end edge before collision`() {
        val preview =
            calculateMenuEdgePreviewFlingOffset(
                velocityX = -2400f,
                scrollValue = 760,
                maxScroll = 900,
                viewportWidthPx = 240,
            )

        assertTrue(preview < 0f)
    }

    @Test
    fun `calculateMenuEdgePreviewFlingOffset previews an impending end collision from a strong fling`() {
        val preview =
            calculateMenuEdgePreviewFlingOffset(
                velocityX = -5200f,
                scrollValue = 420,
                maxScroll = 900,
                viewportWidthPx = 240,
            )

        assertTrue(preview < 0f)
    }

    @Test
    fun `calculateMenuEdgePreviewFlingOffset keeps the first fling preview instead of retriggering`() {
        val firstPreview =
            calculateMenuEdgePreviewFlingOffset(
                velocityX = -2400f,
                scrollValue = 300,
                maxScroll = 900,
                viewportWidthPx = 240,
            )

        val retainedPreview =
            calculateMenuEdgePreviewFlingOffset(
                velocityX = -2400f,
                scrollValue = 500,
                maxScroll = 900,
                viewportWidthPx = 240,
                retainedPreviewOffsetPx = firstPreview,
            )

        assertEquals(firstPreview, retainedPreview, 0.0001f)
    }
}
