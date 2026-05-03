package com.lomo.ui.component.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: draggable scrollbar interaction policy helpers.
 * - Behavior focus: dragging should keep the thumb on its local drag position instead of snapping
 *   back to externally recomputed scroll fractions, external thumb sync should stay disabled
 *   while a drag is active, and pointer events should be claimed by the scrollbar only when the
 *   initial touch falls inside its end-aligned touch zone.
 * - Observable outcomes: chosen displayed thumb offset, Boolean external-sync policy, and
 *   point-in-touch-zone classification.
 * - Red phase: Fails before the fix because the thumb is still drawn from the external scroll
 *   fraction during drag, which causes visible jump-back and makes dragging feel broken; and
 *   because there is no policy yet to decide whether a press near the right edge belongs to the
 *   scrollbar or to the underlying memo card.
 * - Excludes: Compose pointer input dispatch, LazyList measurement accuracy, and fade animation.
 */
class DraggableScrollbarInteractionPolicyTest {
    @Test
    fun `dragging displays local thumb offset instead of external scroll offset`() {
        assertEquals(
            132f,
            resolveDisplayedThumbOffsetPx(
                isThumbDragged = true,
                draggedThumbOffsetPx = 132f,
                settledThumbOffsetPx = 84f,
            ),
            0.001f,
        )
    }

    @Test
    fun `idle state displays settled thumb offset`() {
        assertEquals(
            84f,
            resolveDisplayedThumbOffsetPx(
                isThumbDragged = false,
                draggedThumbOffsetPx = 132f,
                settledThumbOffsetPx = 84f,
            ),
            0.001f,
        )
    }

    @Test
    fun `external sync stays disabled while drag is active`() {
        assertFalse(
            shouldSyncDraggedThumbOffsetFromExternal(
                visible = true,
                isThumbDragged = true,
            ),
        )
    }

    @Test
    fun `external sync resumes after drag ends`() {
        assertTrue(
            shouldSyncDraggedThumbOffsetFromExternal(
                visible = true,
                isThumbDragged = false,
            ),
        )
    }

    @Test
    fun `point inside end-aligned touch zone is detected`() {
        assertTrue(
            isPointInScrollbarTouchZone(
                pointerXPx = 980f,
                canvasWidthPx = 1000f,
                touchTargetWidthPx = 96f,
            ),
        )
    }

    @Test
    fun `point on touch-zone start boundary is included`() {
        assertTrue(
            isPointInScrollbarTouchZone(
                pointerXPx = 904f,
                canvasWidthPx = 1000f,
                touchTargetWidthPx = 96f,
            ),
        )
    }

    @Test
    fun `point left of touch zone is rejected so memo cards keep gestures`() {
        assertFalse(
            isPointInScrollbarTouchZone(
                pointerXPx = 800f,
                canvasWidthPx = 1000f,
                touchTargetWidthPx = 96f,
            ),
        )
    }

    @Test
    fun `negative pointer x is rejected`() {
        assertFalse(
            isPointInScrollbarTouchZone(
                pointerXPx = -10f,
                canvasWidthPx = 1000f,
                touchTargetWidthPx = 96f,
            ),
        )
    }

    @Test
    fun `zero canvas width yields no touch zone so all presses are rejected`() {
        assertFalse(
            isPointInScrollbarTouchZone(
                pointerXPx = 0f,
                canvasWidthPx = 0f,
                touchTargetWidthPx = 96f,
            ),
        )
    }

    @Test
    fun `touch target wider than canvas falls back to whole canvas`() {
        assertTrue(
            isPointInScrollbarTouchZone(
                pointerXPx = 5f,
                canvasWidthPx = 80f,
                touchTargetWidthPx = 200f,
            ),
        )
    }
}
