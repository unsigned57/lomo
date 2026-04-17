package com.lomo.ui.component.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: draggable scrollbar interaction policy helpers.
 * - Behavior focus: dragging should keep the thumb on its local drag position instead of snapping
 *   back to externally recomputed scroll fractions, and external thumb sync should stay disabled
 *   while a drag is active.
 * - Observable outcomes: chosen displayed thumb offset and Boolean external-sync policy.
 * - Red phase: Fails before the fix because the thumb is still drawn from the external scroll
 *   fraction during drag, which causes visible jump-back and makes dragging feel broken.
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
}
