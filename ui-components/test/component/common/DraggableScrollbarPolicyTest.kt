package com.lomo.ui.component.common

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: draggable scrollbar policy helpers (pixel-based fraction math + boundary clamp).
 * - Behavior focus: interactive scrollbars should always be reachable for scrollable content
 *   (visible at rest with a faint idle thumb), keep a fixed thumb size, map between scroll
 *   position and thumb fraction round-trip-consistently for both ScrollState-like and
 *   LazyListState-like containers, and pin the thumb to the rail extremes when the list cannot
 *   scroll any further in that direction (so a hard fling to the loaded bottom shows fraction = 1
 *   even when paging is still about to extend the dataset).
 * - Observable outcomes: Boolean visibility decisions, fixed thumb extent, drag-to-scroll mapping
 *   outputs, and boundary-clamped fraction.
 * - Red phase: Fails before the fix because forward and inverse Lazy-list mappings used different
 *   "scrollable item span" denominators (`totalCount-1` vs `totalCount-visibleCount`) so dragging
 *   to fraction=1 left the list short of its real bottom; and because paging append grew
 *   totalItemsCount mid-scroll, retroactively dragging the thumb backward to ~0.66 even though
 *   the user had just flung the list to its rendered bottom.
 * - Excludes: Compose pointer input wiring, animation timing, and platform EditText scrollbars.
 */
class DraggableScrollbarPolicyTest : UiComponentsFunSpec() {
    init {
        test("shows scrollbar for scrollable content at rest") {
        (shouldShowDraggableScrollbar(canScroll = true)) shouldBe true
        }
    }

    init {
        test("hides scrollbar when content cannot scroll") {
        (shouldShowDraggableScrollbar(canScroll = false)) shouldBe false
        }
    }

    init {
        test("fixed thumb size does not depend on content length") {
        val shortContent =
            resolveScrollbarThumbMetrics(
                trackExtentPx = 240f,
                thumbExtentPx = 40f,
                scrollFraction = 0.4f,
            )
        val longContent =
            resolveScrollbarThumbMetrics(
                trackExtentPx = 240f,
                thumbExtentPx = 40f,
                scrollFraction = 0.4f,
            )

        (shortContent.thumbExtentPx) shouldBe ((40f) plusOrMinus (0.001f))
        (longContent.thumbExtentPx) shouldBe ((shortContent.thumbExtentPx) plusOrMinus (0.001f))
        (shortContent.thumbOffsetPx) shouldBe ((80f) plusOrMinus (0.001f))
        }
    }

    init {
        test("drag fraction maps directly to scroll state offset") {
        (mapThumbFractionToScrollOffset(
                thumbFraction = 0.3f,
                maxScrollOffset = 1200,
            )) shouldBe (360)
        }
    }

    init {
        test("drag uses accumulated thumb offset instead of moving pointer frame") {
        (mapDraggedThumbOffsetToFraction(
                draggedThumbOffsetPx = 100f,
                trackExtentPx = 240f,
                thumbExtentPx = 40f,
            )) shouldBe ((0.5f) plusOrMinus (0.001f))
        }
    }

    init {
        test("drag mapping clamps when dragged beyond track end") {
        (mapDraggedThumbOffsetToFraction(
                draggedThumbOffsetPx = 280f,
                trackExtentPx = 240f,
                thumbExtentPx = 40f,
            )) shouldBe ((1f) plusOrMinus (0.001f))
        }
    }

    init {
        test("pixel-based thumb fraction is zero at list top") {
        val fractionAtTop =
            resolveLazyListThumbFractionByPixels(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 0,
                avgItemSizePx = 120f,
                viewportSizePx = 800,
                totalItemsCount = 30,
            )

        (fractionAtTop) shouldBe ((0f) plusOrMinus (0.001f))
        }
    }

    init {
        test("pixel-based thumb fraction reaches one at list bottom") {
        // 30 items × 120px = 3600px content, viewport 800px → maxScroll = 2800px.
        // First-visible at item 23 with 40px past = scrolled 23*120 + 40 = 2800px.
        val fractionAtBottom =
            resolveLazyListThumbFractionByPixels(
                firstVisibleItemIndex = 23,
                firstVisibleItemScrollOffsetPx = 40,
                avgItemSizePx = 120f,
                viewportSizePx = 800,
                totalItemsCount = 30,
            )

        (fractionAtBottom) shouldBe ((1f) plusOrMinus (0.001f))
        }
    }

    init {
        test("pixel-based thumb fraction stays at zero when content fits viewport") {
        val fraction =
            resolveLazyListThumbFractionByPixels(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 0,
                avgItemSizePx = 120f,
                viewportSizePx = 1000,
                totalItemsCount = 5,
            )

        (fraction) shouldBe ((0f) plusOrMinus (0.001f))
        }
    }

    init {
        test("pixel-based thumb fraction advances smoothly within the same first item") {
        val earlier =
            resolveLazyListThumbFractionByPixels(
                firstVisibleItemIndex = 10,
                firstVisibleItemScrollOffsetPx = 12,
                avgItemSizePx = 120f,
                viewportSizePx = 800,
                totalItemsCount = 100,
            )
        val later =
            resolveLazyListThumbFractionByPixels(
                firstVisibleItemIndex = 10,
                firstVisibleItemScrollOffsetPx = 72,
                avgItemSizePx = 120f,
                viewportSizePx = 800,
                totalItemsCount = 100,
            )

        (later > earlier) shouldBe true
        }
    }

    init {
        test("pixel-based thumb fraction is monotonic across later first visible indices") {
        val earlier =
            resolveLazyListThumbFractionByPixels(
                firstVisibleItemIndex = 3,
                firstVisibleItemScrollOffsetPx = 0,
                avgItemSizePx = 120f,
                viewportSizePx = 800,
                totalItemsCount = 100,
            )
        val later =
            resolveLazyListThumbFractionByPixels(
                firstVisibleItemIndex = 18,
                firstVisibleItemScrollOffsetPx = 0,
                avgItemSizePx = 120f,
                viewportSizePx = 800,
                totalItemsCount = 100,
            )

        (later > earlier) shouldBe true
        }
    }

    init {
        test("inverse mapping at fraction one lands at end of scrollable span") {
        val target =
            mapThumbFractionToLazyListPositionByPixels(
                fraction = 1f,
                avgItemSizePx = 120f,
                viewportSizePx = 800,
                totalItemsCount = 30,
            )

        // maxScroll = 30*120 - 800 = 2800. targetIndex = 2800/120 = 23. intra = 2800 - 23*120 = 40.
        (target.index) shouldBe (23)
        (target.scrollOffsetPx) shouldBe (40)
        }
    }

    init {
        test("inverse mapping at fraction zero lands at item zero with no offset") {
        val target =
            mapThumbFractionToLazyListPositionByPixels(
                fraction = 0f,
                avgItemSizePx = 120f,
                viewportSizePx = 800,
                totalItemsCount = 30,
            )

        (target.index) shouldBe (0)
        (target.scrollOffsetPx) shouldBe (0)
        }
    }

    init {
        test("forward and inverse mapping round-trip at the bottom") {
        val avg = 120f
        val viewport = 800
        val total = 30
        val target =
            mapThumbFractionToLazyListPositionByPixels(
                fraction = 1f,
                avgItemSizePx = avg,
                viewportSizePx = viewport,
                totalItemsCount = total,
            )
        val recoveredFraction =
            resolveLazyListThumbFractionByPixels(
                firstVisibleItemIndex = target.index,
                firstVisibleItemScrollOffsetPx = target.scrollOffsetPx,
                avgItemSizePx = avg,
                viewportSizePx = viewport,
                totalItemsCount = total,
            )

        (recoveredFraction) shouldBe ((1f) plusOrMinus (0.001f))
        }
    }

    init {
        test("forward and inverse mapping round-trip at the midpoint") {
        val avg = 120f
        val viewport = 800
        val total = 50
        val target =
            mapThumbFractionToLazyListPositionByPixels(
                fraction = 0.5f,
                avgItemSizePx = avg,
                viewportSizePx = viewport,
                totalItemsCount = total,
            )
        val recoveredFraction =
            resolveLazyListThumbFractionByPixels(
                firstVisibleItemIndex = target.index,
                firstVisibleItemScrollOffsetPx = target.scrollOffsetPx,
                avgItemSizePx = avg,
                viewportSizePx = viewport,
                totalItemsCount = total,
            )

        (recoveredFraction) shouldBe ((0.5f) plusOrMinus (0.005f))
        }
    }

    init {
        test("inverse mapping rejects degenerate avg item size") {
        val target =
            mapThumbFractionToLazyListPositionByPixels(
                fraction = 0.5f,
                avgItemSizePx = 0f,
                viewportSizePx = 800,
                totalItemsCount = 30,
            )

        (target.index) shouldBe (0)
        (target.scrollOffsetPx) shouldBe (0)
        }
    }

    init {
        test("boundary clamp pins fraction to one when forward scrolling is blocked") {
        (resolveLazyListThumbFractionAtBoundaries(
                rawFraction = 0.66f,
                canScrollBackward = true,
                canScrollForward = false,
            )) shouldBe ((1f) plusOrMinus (0.001f))
        }
    }

    init {
        test("boundary clamp pins fraction to zero when backward scrolling is blocked") {
        (resolveLazyListThumbFractionAtBoundaries(
                rawFraction = 0.4f,
                canScrollBackward = false,
                canScrollForward = true,
            )) shouldBe ((0f) plusOrMinus (0.001f))
        }
    }

    init {
        test("boundary clamp keeps raw fraction when scrolling is unrestricted in both directions") {
        (resolveLazyListThumbFractionAtBoundaries(
                rawFraction = 0.42f,
                canScrollBackward = true,
                canScrollForward = true,
            )) shouldBe ((0.42f) plusOrMinus (0.001f))
        }
    }
}
