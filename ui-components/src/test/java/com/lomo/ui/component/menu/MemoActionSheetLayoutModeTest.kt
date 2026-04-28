/*
 * Test Contract:
 * - Unit under test: MemoActionSheet layout mode resolution
 * - Behavior focus: equalWidthActions + useHorizontalScroll=false produces static Row with uniform widths.
 * - Observable outcomes: resolveMemoActionRowLayoutMode returns correct mode for each flag combination.
 * - Red phase: Fails before fix when Trash menu icons are not split evenly.
 * - Excludes: full Compose rendering, drag-to-reorder.
 */
package com.lomo.ui.component.menu

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoActionSheetLayoutModeTest {
    @Test
    fun `resolveMemoActionRowLayoutMode uses equal width row when horizontal scroll is disabled`() {
        assertEquals(
            MemoActionRowLayoutMode.EQUAL_WIDTH_STATIC,
            resolveMemoActionRowLayoutMode(
                equalWidthActions = true,
                useHorizontalScroll = false,
            ),
        )
    }

    @Test
    fun `resolveMemoActionRowLayoutMode keeps lazy row when equal width actions are disabled`() {
        assertEquals(
            MemoActionRowLayoutMode.LAZY_ROW,
            resolveMemoActionRowLayoutMode(
                equalWidthActions = false,
                useHorizontalScroll = false,
            ),
        )
    }

    @Test
    fun `resolveMemoActionRowLayoutMode keeps lazy row when horizontal scroll stays enabled`() {
        assertEquals(
            MemoActionRowLayoutMode.LAZY_ROW,
            resolveMemoActionRowLayoutMode(
                equalWidthActions = true,
                useHorizontalScroll = true,
            ),
        )
    }
}
