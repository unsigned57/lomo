/*
 * Test Contract:
 * - Unit under test: MemoActionSheet layout mode resolution
 * - Behavior focus: equalWidthActions + useHorizontalScroll=false produces static Row with uniform widths.
 * - Observable outcomes: resolveMemoActionRowLayoutMode returns correct mode for each flag combination.
 * - Red phase: Fails before fix when Trash menu icons are not split evenly.
 * - Excludes: full Compose rendering, drag-to-reorder.
 */

package com.lomo.ui.component.menu

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

class MemoActionSheetLayoutModeTest : UiComponentsFunSpec() {
    init {
        test("resolveMemoActionRowLayoutMode uses equal width row when horizontal scroll is disabled") {
        (resolveMemoActionRowLayoutMode(
                equalWidthActions = true,
                useHorizontalScroll = false,
            )) shouldBe (MemoActionRowLayoutMode.EQUAL_WIDTH_STATIC)
        }
    }

    init {
        test("resolveMemoActionRowLayoutMode keeps lazy row when equal width actions are disabled") {
        (resolveMemoActionRowLayoutMode(
                equalWidthActions = false,
                useHorizontalScroll = false,
            )) shouldBe (MemoActionRowLayoutMode.LAZY_ROW)
        }
    }

    init {
        test("resolveMemoActionRowLayoutMode keeps lazy row when horizontal scroll stays enabled") {
        (resolveMemoActionRowLayoutMode(
                equalWidthActions = true,
                useHorizontalScroll = true,
            )) shouldBe (MemoActionRowLayoutMode.LAZY_ROW)
        }
    }
}
