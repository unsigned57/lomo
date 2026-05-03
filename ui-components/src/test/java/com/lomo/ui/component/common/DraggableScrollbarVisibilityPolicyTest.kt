package com.lomo.ui.component.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: draggable scrollbar visual-state policy helpers.
 * - Behavior focus: the scrollbar must always be discoverable for scrollable content (idle thumb
 *   is faintly visible) and must escalate its visual prominence based on interaction state, with
 *   active drag winning over passive scrolling and idle being the most subdued.
 * - Observable outcomes: resolved [ScrollbarThumbVisualState] and the alpha value mapped from it.
 * - Red phase: Fails before the fix because the scrollbar relies on a single idle alpha value
 *   and on a "recently scrolled" gate that hides the thumb when the list is at rest, leaving
 *   users without any affordance to grab.
 * - Excludes: AnimatedVisibility timing, color sourcing from the M3 color scheme, and width
 *   animation.
 */
class DraggableScrollbarVisibilityPolicyTest {
    @Test
    fun `idle visual state when nothing is happening`() {
        assertEquals(
            ScrollbarThumbVisualState.Idle,
            resolveScrollbarThumbVisualState(
                isThumbDragged = false,
                isScrollInProgress = false,
                recentlyScrolled = false,
            ),
        )
    }

    @Test
    fun `scroll in progress upgrades to active state`() {
        assertEquals(
            ScrollbarThumbVisualState.Active,
            resolveScrollbarThumbVisualState(
                isThumbDragged = false,
                isScrollInProgress = true,
                recentlyScrolled = false,
            ),
        )
    }

    @Test
    fun `recently scrolled stays in active state during fade-out window`() {
        assertEquals(
            ScrollbarThumbVisualState.Active,
            resolveScrollbarThumbVisualState(
                isThumbDragged = false,
                isScrollInProgress = false,
                recentlyScrolled = true,
            ),
        )
    }

    @Test
    fun `thumb dragged wins over scroll in progress`() {
        assertEquals(
            ScrollbarThumbVisualState.Drag,
            resolveScrollbarThumbVisualState(
                isThumbDragged = true,
                isScrollInProgress = true,
                recentlyScrolled = true,
            ),
        )
    }

    @Test
    fun `idle alpha is strictly lower than active alpha`() {
        val idle = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Idle)
        val active = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Active)
        assertTrue("idle alpha must stay below active alpha", idle < active)
    }

    @Test
    fun `drag alpha is highest`() {
        val active = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Active)
        val drag = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Drag)
        assertTrue("drag alpha must rise above active alpha", drag > active)
    }

    @Test
    fun `idle alpha stays positive so users can discover the scrollbar at rest`() {
        assertTrue(resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Idle) > 0f)
    }

    @Test
    fun `each visual state maps to a distinct alpha`() {
        val idle = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Idle)
        val active = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Active)
        val drag = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Drag)
        assertNotEquals(idle, active)
        assertNotEquals(active, drag)
        assertNotEquals(idle, drag)
    }
}
