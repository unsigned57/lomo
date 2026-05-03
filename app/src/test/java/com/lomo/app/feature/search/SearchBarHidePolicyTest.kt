package com.lomo.app.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SearchBarHidePolicy shouldAllowSearchBarScroll(...) and
 *   resolveSyncedSearchBarOffsetPx(...)
 * - Behavior focus: the search capsule must only react when the result list can really scroll,
 *   but once it can scroll, the capsule should move from the first consumed scroll delta so it
 *   stays visually synchronized with memo cards.
 * - Observable outcomes: Boolean allow-scroll decisions for non-scrollable, scrollable at top,
 *   partially scrolled, and past-first-item list snapshots; resolved search-bar offsets after
 *   upward/downward consumed list deltas.
 * - Red phase: Fails before the fix because shouldAllowSearchBarHide blocks scroll delegation
 *   until the first visible item offset crosses a threshold, so the search capsule lags behind
 *   memo cards near the top of a scrollable result list.
 * - Excludes: Compose pointer input, animation frames, and memo item rendering.
 *
 * Test Change Justification:
 * - Reason category: product contract correction after interaction review.
 * - Old behavior/assertion being replaced: scrollable-but-below-threshold snapshots returned false.
 * - Why old assertion is no longer correct: it delays search capsule movement until memo cards
 *   have already moved underneath it, which makes the floating capsule feel detached.
 * - Coverage preserved by: non-scrollable snapshots still return false, while scrollable snapshots
 *   now prove immediate synchronized movement.
 * - Why this is not fitting the test to the implementation: the current implementation still
 *   uses the threshold gate and fails the below-threshold scrollable assertion before the fix.
 */
class SearchBarHidePolicyTest {
    @Test
    fun `non scrollable result lists never allow search bar scroll`() {
        assertFalse(
            shouldAllowSearchBarScroll(
                snapshot =
                    SearchBarHideSnapshot(
                        canScrollContent = false,
                        firstVisibleItemIndex = 0,
                        firstVisibleItemScrollOffsetPx = 0,
                    ),
            ),
        )
    }

    @Test
    fun `scrollable result lists at the top allow synchronized search bar movement`() {
        assertTrue(
            shouldAllowSearchBarScroll(
                snapshot =
                    SearchBarHideSnapshot(
                        canScrollContent = true,
                        firstVisibleItemIndex = 0,
                        firstVisibleItemScrollOffsetPx = 0,
                    ),
            ),
        )
    }

    @Test
    fun `scrollable result lists below the old threshold still allow synchronized movement`() {
        assertTrue(
            shouldAllowSearchBarScroll(
                snapshot =
                    SearchBarHideSnapshot(
                        canScrollContent = true,
                        firstVisibleItemIndex = 0,
                        firstVisibleItemScrollOffsetPx = 48,
                    ),
            ),
        )
    }

    @Test
    fun `scrollable result lists beyond the old threshold keep allowing search bar movement`() {
        assertTrue(
            shouldAllowSearchBarScroll(
                snapshot =
                    SearchBarHideSnapshot(
                        canScrollContent = true,
                        firstVisibleItemIndex = 0,
                        firstVisibleItemScrollOffsetPx = 96,
                    ),
            ),
        )
    }

    @Test
    fun `scrolling past the first item keeps allowing search bar movement`() {
        assertTrue(
            shouldAllowSearchBarScroll(
                snapshot =
                    SearchBarHideSnapshot(
                        canScrollContent = true,
                        firstVisibleItemIndex = 1,
                        firstVisibleItemScrollOffsetPx = 0,
                    ),
            ),
        )
    }

    @Test
    fun `upward consumed list movement moves search bar by the same distance`() {
        assertEquals(
            24f,
            resolveSyncedSearchBarOffsetPx(
                currentOffsetPx = 0f,
                consumedContentDeltaYPx = -24f,
                maxOffsetPx = 96f,
            ),
            0.001f,
        )
    }

    @Test
    fun `downward consumed list movement reveals search bar by the same distance`() {
        assertEquals(
            48f,
            resolveSyncedSearchBarOffsetPx(
                currentOffsetPx = 80f,
                consumedContentDeltaYPx = 32f,
                maxOffsetPx = 96f,
            ),
            0.001f,
        )
    }

    @Test
    fun `synchronized search bar offset clamps to measured bar height`() {
        assertEquals(
            96f,
            resolveSyncedSearchBarOffsetPx(
                currentOffsetPx = 80f,
                consumedContentDeltaYPx = -60f,
                maxOffsetPx = 96f,
            ),
            0.001f,
        )
    }

    @Test
    fun `zero consumed movement keeps current search bar offset`() {
        assertEquals(
            20f,
            resolveSyncedSearchBarOffsetPx(
                currentOffsetPx = 20f,
                consumedContentDeltaYPx = 0f,
                maxOffsetPx = 96f,
            ),
            0.001f,
        )
    }
}
