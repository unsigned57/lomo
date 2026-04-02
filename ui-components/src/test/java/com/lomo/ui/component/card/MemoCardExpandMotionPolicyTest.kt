package com.lomo.ui.component.card

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo-card expand motion policy.
 * - Behavior focus: expandable memo cards use a vertical-only body transition so switching from collapsed summary to full markdown never relies on whole-body size interpolation.
 * - Observable outcomes: resolved body transition mode for expandable vs non-expandable cards.
 * - Red phase: Fails before the fix because the dedicated expand-motion policy does not exist, so memo cards cannot opt out of whole-body animateContentSize interpolation.
 * - Excludes: Compose animation runtime behavior, pixel rendering, and markdown parsing.
 */
class MemoCardExpandMotionPolicyTest {
    @Test
    fun `expandable memo cards use vertical visibility transition`() {
        assertEquals(
            MemoCardBodyTransitionMode.VerticalVisibility,
            resolveMemoCardBodyTransitionMode(shouldShowExpand = true),
        )
    }

    @Test
    fun `non-expandable memo cards keep snap body mode`() {
        assertEquals(
            MemoCardBodyTransitionMode.Snap,
            resolveMemoCardBodyTransitionMode(shouldShowExpand = false),
        )
    }
}
