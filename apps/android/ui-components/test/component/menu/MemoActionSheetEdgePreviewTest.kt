package com.lomo.ui.component.menu

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: lazy-row swipe affordance progress helper for MemoActionSheet.
 * - Behavior focus: menu swipe affordance should stay pinned to the leading edge at list start,
 *   advance smoothly through the middle, and snap to the trailing edge at list end.
 * - Observable outcomes: resolved affordance progress fraction for non-scrollable, leading-edge,
 *   middle-scroll, and trailing-edge snapshots.
 * - Red phase: Fails before the fix because the old edge-preview tests still reference retired
 *   helper APIs instead of the current lazy-row swipe-affordance progress contract.
 * - Excludes: Compose LazyRow rendering, overscroll animation, and reorder gesture wiring.
 *
 * Test Change Justification:
 * - Reason category: pure refactor preserved behavior.
 * - Replaced assertion reference: retired `calculateMenuEdgePreviewOffset(...)` and
 *   `calculateMenuEdgePreviewFlingOffset(...)` calls.
 * - Previous assertion is no longer correct because the production menu row now computes boundary
 *   feedback from lazy-row progress state rather than predictive preview offsets.
 * - Retained coverage: the tests still lock the same user-facing boundary signal by asserting
 *   start, middle, and end-edge affordance outputs on the current pure helper.
 * - This is not changing the test to fit the implementation because the obsolete API no longer
 *   exists in the current design and the replacement assertions still protect horizontal boundary
 *   feedback behavior.
 */
class MemoActionSheetEdgePreviewTest : UiComponentsFunSpec() {
    init {
        test("swipe affordance progress is zero when content cannot scroll") {
        val progress =
            resolveLazyRowSwipeAffordanceProgress(
                LazyRowSwipeAffordanceSnapshot(
                    canScrollBackward = false,
                    canScrollForward = false,
                    totalItemsCount = 1,
                    firstVisibleItemIndex = null,
                ),
            )

        (progress) shouldBe ((0f) plusOrMinus (0.0001f))
        }
    }

    init {
        test("swipe affordance progress stays at the leading edge when only forward scroll remains") {
        val progress =
            resolveLazyRowSwipeAffordanceProgress(
                LazyRowSwipeAffordanceSnapshot(
                    canScrollBackward = false,
                    canScrollForward = true,
                    totalItemsCount = 6,
                    firstVisibleItemIndex = 0,
                    firstVisibleItemOffsetPx = 0,
                    firstVisibleItemSizePx = 120,
                    viewportStartOffsetPx = 0,
                ),
            )

        (progress) shouldBe ((0f) plusOrMinus (0.0001f))
        }
    }

    init {
        test("swipe affordance progress advances smoothly through the middle of the row") {
        val progress =
            resolveLazyRowSwipeAffordanceProgress(
                LazyRowSwipeAffordanceSnapshot(
                    canScrollBackward = true,
                    canScrollForward = true,
                    totalItemsCount = 6,
                    firstVisibleItemIndex = 2,
                    firstVisibleItemOffsetPx = -30,
                    firstVisibleItemSizePx = 120,
                    viewportStartOffsetPx = 0,
                ),
            )

        (progress) shouldBe ((0.45f) plusOrMinus (0.0001f))
        }
    }

    init {
        test("swipe affordance progress snaps to the trailing edge when only backward scroll remains") {
        val progress =
            resolveLazyRowSwipeAffordanceProgress(
                LazyRowSwipeAffordanceSnapshot(
                    canScrollBackward = true,
                    canScrollForward = false,
                    totalItemsCount = 6,
                    firstVisibleItemIndex = 5,
                    firstVisibleItemOffsetPx = -10,
                    firstVisibleItemSizePx = 120,
                    viewportStartOffsetPx = 0,
                ),
            )

        (progress) shouldBe ((1f) plusOrMinus (0.0001f))
        }
    }
}
