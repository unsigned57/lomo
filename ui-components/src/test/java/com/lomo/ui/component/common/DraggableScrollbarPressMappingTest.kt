package com.lomo.ui.component.common

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: resolveInitialThumbOffsetFromPress
 * - Behavior focus: When a long-press is confirmed on the scrollbar track, the thumb must
 *   center on the press position so the user sees it jump to where their finger landed.
 *   A long-press that lands on the thumb itself must leave the thumb in place, so the
 *   subsequent drag continues from its current position without a visible jump.
 * - Observable outcomes: returned initial thumb offset in pixels.
 * - Red phase: Verified by asserting offset calculations fail when logic is stripped.
 * - Excludes: Compose gesture dispatch (long-press detection, drag dispatch), scroll fraction
 *   math, thumb fade animation, systemGestureExclusion routing.
 */
class DraggableScrollbarPressMappingTest {
    @Test
    fun `tap on track centers thumb on press position`() {
        val offset =
            resolveInitialThumbOffsetFromPress(
                pressY = 200f,
                currentThumbOffsetPx = 40f,
                thumbExtentPx = 60f,
                trackHeightPx = 400f,
            )
        assertEquals(170f, offset, 0.001f)
    }

    @Test
    fun `tap on thumb leaves thumb offset unchanged`() {
        val offset =
            resolveInitialThumbOffsetFromPress(
                pressY = 55f,
                currentThumbOffsetPx = 40f,
                thumbExtentPx = 60f,
                trackHeightPx = 400f,
            )
        assertEquals(40f, offset, 0.001f)
    }

    @Test
    fun `tap above track clamps thumb to top`() {
        val offset =
            resolveInitialThumbOffsetFromPress(
                pressY = -50f,
                currentThumbOffsetPx = 200f,
                thumbExtentPx = 60f,
                trackHeightPx = 400f,
            )
        assertEquals(0f, offset, 0.001f)
    }

    @Test
    fun `tap below track clamps thumb to bottom`() {
        val offset =
            resolveInitialThumbOffsetFromPress(
                pressY = 1000f,
                currentThumbOffsetPx = 200f,
                thumbExtentPx = 60f,
                trackHeightPx = 400f,
            )
        assertEquals(340f, offset, 0.001f)
    }

    @Test
    fun `tap on track with zero track height yields zero offset`() {
        val offset =
            resolveInitialThumbOffsetFromPress(
                pressY = 50f,
                currentThumbOffsetPx = 0f,
                thumbExtentPx = 60f,
                trackHeightPx = 0f,
            )
        assertEquals(0f, offset, 0.001f)
    }
}
