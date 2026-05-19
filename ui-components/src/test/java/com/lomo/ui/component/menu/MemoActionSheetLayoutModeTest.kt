/*
 * Test Contract:
 * - Unit under test: MemoActionSheet layout mode resolution
 * - Behavior focus: equalWidthActions + useHorizontalScroll=false produces static Row with uniform widths.
 * - Observable outcomes: resolveMemoActionRowLayoutMode returns correct mode for each flag combination.
 * - Red phase: Fails before fix when Trash menu icons are not split evenly.
 * - Excludes: full Compose rendering, drag-to-reorder.
 */

package com.lomo.ui.component.menu

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


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

        test("resolveMemoActionRowLayoutMode keeps lazy row when equal width actions are disabled") {
            (resolveMemoActionRowLayoutMode(
                equalWidthActions = false,
                useHorizontalScroll = false,
            )) shouldBe (MemoActionRowLayoutMode.LAZY_ROW)
        }

        test("resolveMemoActionRowLayoutMode keeps lazy row when horizontal scroll stays enabled") {
            (resolveMemoActionRowLayoutMode(
                equalWidthActions = true,
                useHorizontalScroll = true,
            )) shouldBe (MemoActionRowLayoutMode.LAZY_ROW)
        }
    }
}
