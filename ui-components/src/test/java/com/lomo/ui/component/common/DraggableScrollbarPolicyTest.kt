package com.lomo.ui.component.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: draggable scrollbar policy helpers.
 * - Behavior focus: interactive scrollbars should appear only for scrollable content during
 *   active or recent interaction, keep a fixed thumb size, and map thumb dragging plus
 *   passive LazyList scrolling to stable positions for both ScrollState-like and
 *   LazyListState-like containers.
 * - Observable outcomes: Boolean visibility decisions, fixed thumb extent, and drag-to-scroll
 *   mapping outputs.
 * - Red phase: Fails before the fix because LazyList thumb progress is derived from the current
 *   visible-item average height, so normal scrolling through mixed-height memos makes the thumb
 *   jump even when the first visible item progresses smoothly.
 * - Excludes: Compose pointer input wiring, animation timing, and platform EditText scrollbars.
 */
class DraggableScrollbarPolicyTest {
    @Test
    fun `shows scrollbar while dragging even after content scrolling stops`() {
        assertTrue(
            shouldShowDraggableScrollbar(
                canScroll = true,
                isScrollInProgress = false,
                isThumbDragged = true,
                recentlyScrolled = false,
            ),
        )
    }

    @Test
    fun `hides scrollbar when content cannot scroll`() {
        assertFalse(
            shouldShowDraggableScrollbar(
                canScroll = false,
                isScrollInProgress = true,
                isThumbDragged = true,
                recentlyScrolled = true,
            ),
        )
    }

    @Test
    fun `fixed thumb size does not depend on content length`() {
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

        assertEquals(40f, shortContent.thumbExtentPx, 0.001f)
        assertEquals(shortContent.thumbExtentPx, longContent.thumbExtentPx, 0.001f)
        assertEquals(80f, shortContent.thumbOffsetPx, 0.001f)
    }

    @Test
    fun `drag fraction maps directly to scroll state offset`() {
        assertEquals(
            360,
            mapThumbFractionToScrollOffset(
                thumbFraction = 0.3f,
                maxScrollOffset = 1200,
            ),
        )
    }

    @Test
    fun `drag fraction maps to lazy list item index and intra item offset`() {
        val mapping =
            mapThumbFractionToLazyListPosition(
                thumbFraction = 0.5f,
                totalItemsCount = 100,
                targetItemSizePx = 120,
            )

        assertEquals(49, mapping.index)
        assertEquals(60, mapping.scrollOffsetPx)
    }

    @Test
    fun `lazy list drag mapping clamps to last item top when size is unknown`() {
        val mapping =
            mapThumbFractionToLazyListPosition(
                thumbFraction = 1f,
                totalItemsCount = 5,
                targetItemSizePx = null,
            )

        assertEquals(4, mapping.index)
        assertEquals(0, mapping.scrollOffsetPx)
    }

    @Test
    fun `drag uses accumulated thumb offset instead of moving pointer frame`() {
        assertEquals(
            0.5f,
            mapDraggedThumbOffsetToFraction(
                draggedThumbOffsetPx = 100f,
                trackExtentPx = 240f,
                thumbExtentPx = 40f,
            ),
            0.001f,
        )
    }

    @Test
    fun `drag mapping clamps when dragged beyond track end`() {
        assertEquals(
            1f,
            mapDraggedThumbOffsetToFraction(
                draggedThumbOffsetPx = 280f,
                trackExtentPx = 240f,
                thumbExtentPx = 40f,
            ),
            0.001f,
        )
    }

    @Test
    fun `lazy list thumb fraction ignores later visible item heights`() {
        val compactTrailingItems =
            resolveLazyListThumbFraction(
                firstVisibleItemIndex = 10,
                firstVisibleItemScrollOffsetPx = 40,
                firstVisibleItemSizePx = 120,
                totalItemsCount = 100,
            )
        val tallTrailingItems =
            resolveLazyListThumbFraction(
                firstVisibleItemIndex = 10,
                firstVisibleItemScrollOffsetPx = 40,
                firstVisibleItemSizePx = 120,
                totalItemsCount = 100,
            )

        assertEquals(compactTrailingItems, tallTrailingItems, 0.001f)
    }

    @Test
    fun `lazy list thumb fraction advances smoothly within the same first item`() {
        val earlier =
            resolveLazyListThumbFraction(
                firstVisibleItemIndex = 10,
                firstVisibleItemScrollOffsetPx = 12,
                firstVisibleItemSizePx = 120,
                totalItemsCount = 100,
            )
        val later =
            resolveLazyListThumbFraction(
                firstVisibleItemIndex = 10,
                firstVisibleItemScrollOffsetPx = 72,
                firstVisibleItemSizePx = 120,
                totalItemsCount = 100,
            )

        assertTrue(later > earlier)
    }

    @Test
    fun `lazy list thumb fraction is monotonic across later first visible indices`() {
        val earlier =
            resolveLazyListThumbFraction(
                firstVisibleItemIndex = 3,
                firstVisibleItemScrollOffsetPx = 0,
                firstVisibleItemSizePx = 120,
                totalItemsCount = 100,
            )
        val later =
            resolveLazyListThumbFraction(
                firstVisibleItemIndex = 18,
                firstVisibleItemScrollOffsetPx = 0,
                firstVisibleItemSizePx = 120,
                totalItemsCount = 100,
            )

        assertTrue(later > earlier)
    }

    @Test
    fun `lazy list drag mapping falls back to target item top when size is unknown`() {
        val mapping =
            mapThumbFractionToLazyListPosition(
                thumbFraction = 0.5f,
                totalItemsCount = 100,
                targetItemSizePx = null,
            )

        assertEquals(49, mapping.index)
        assertEquals(0, mapping.scrollOffsetPx)
    }
}
