package com.lomo.ui.text

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
import androidx.compose.ui.geometry.Offset

/*
 * Behavior Contract:
 * - Unit under test: cross-paragraph (multi-block) free-copy selection state.
 *
 * Scenarios:
 * - Happy: standard happy path for MemoMultiParagraphSelectionTest.
 * - Boundary: boundary and edge cases for MemoMultiParagraphSelectionTest.
 * - Failure: failure and error scenarios for MemoMultiParagraphSelectionTest.
 * - Must-not-happen: invariants are never violated for MemoMultiParagraphSelectionTest.
 * - Behavior focus: a single MemoMultiParagraphSelection tracks (anchorBlock, anchorOffset,
 *   focusBlock, focusOffset); selectedRangeForBlock yields the correct per-block highlight
 *   range whether the selection is single-block, forward across blocks, or backward; and
 *   selectedText concatenates the live substrings in registration order with "\n" between
 *   blocks. A scope-space hit tester picks the block whose Y band contains a drag position
 *   and falls back to the nearest block by Y distance.
 * - Observable outcomes: MemoMultiParagraphSelection.{hasSelection, selectedRangeForBlock,
 *   selectedText} and resolveMemoTextSelectionHitBlock results given synthetic blocks.
 * - TDD proof: Fails before the new selection scope exists because the symbols are not yet
 *   defined.
 * - Excludes: Compose composition lifecycle, Popup placement, clipboard plumbing.
 */
class MemoMultiParagraphSelectionTest : UiComponentsFunSpec() {
    init {
        test("single block selection range mirrors anchor and focus offsets") {
            val selection =
                MemoMultiParagraphSelection(
                    anchor = MemoTextSelectionEndpoint(blockKey = "a", offset = 0),
                    focus = MemoTextSelectionEndpoint(blockKey = "a", offset = 5),
                )
            val order = listOf<Any>("a")

            (selection.hasSelection(order)) shouldBe true
            (selection.selectedRangeForBlock(blockKey = "a", blockTextLength = 12, blockOrder = order)) shouldBe (0 until 5)
            (selection.selectedText(blocksInOrder = listOf("a" to "Hello world!"))) shouldBe ("Hello")
        }

        test("selectedText concatenates per-block ranges with newline separators in forward order") {
            val selection =
                MemoMultiParagraphSelection(
                    anchor = MemoTextSelectionEndpoint(blockKey = "a", offset = 6),
                    focus = MemoTextSelectionEndpoint(blockKey = "c", offset = 5),
                )
            val blocksInOrder =
                listOf<Pair<Any, String>>(
                    "a" to "Hello world",
                    "b" to "second paragraph",
                    "c" to "third!",
                )

            (selection.selectedText(blocksInOrder)) shouldBe ("world\nsecond paragraph\nthird")
        }

        test("selectedText still concatenates forward when anchor sits after focus in registration order") {
            val selection =
                MemoMultiParagraphSelection(
                    anchor = MemoTextSelectionEndpoint(blockKey = "c", offset = 5),
                    focus = MemoTextSelectionEndpoint(blockKey = "a", offset = 6),
                )
            val blocksInOrder =
                listOf<Pair<Any, String>>(
                    "a" to "Hello world",
                    "b" to "second paragraph",
                    "c" to "third!",
                )

            (selection.selectedText(blocksInOrder)) shouldBe ("world\nsecond paragraph\nthird")
        }

        test("selectedRangeForBlock returns null for blocks outside the selection bounds") {
            val selection =
                MemoMultiParagraphSelection(
                    anchor = MemoTextSelectionEndpoint(blockKey = "a", offset = 6),
                    focus = MemoTextSelectionEndpoint(blockKey = "b", offset = 4),
                )
            val order = listOf<Any>("a", "b", "c")

            (selection.selectedRangeForBlock("a", blockTextLength = 11, blockOrder = order)) shouldBe (6 until 11)
            (selection.selectedRangeForBlock("b", blockTextLength = 16, blockOrder = order)) shouldBe (0 until 4)
            (selection.selectedRangeForBlock("c", blockTextLength = 6, blockOrder = order)) shouldBe null
        }

        test("empty or collapsed selection has no selection or per-block range") {
            val empty = MemoMultiParagraphSelection(anchor = null, focus = null)
            val collapsed =
                MemoMultiParagraphSelection(
                    anchor = MemoTextSelectionEndpoint(blockKey = "a", offset = 3),
                    focus = MemoTextSelectionEndpoint(blockKey = "a", offset = 3),
                )
            val order = listOf<Any>("a")

            (!empty.hasSelection(order)) shouldBe true
            (!collapsed.hasSelection(order)) shouldBe true
            (empty.selectedRangeForBlock("a", blockTextLength = 5, blockOrder = order)) shouldBe null
            (collapsed.selectedRangeForBlock("a", blockTextLength = 5, blockOrder = order)) shouldBe null
        }
    }
}

/*
 * Behavior Contract:
 * - Unit under test: scope-space hit tester used by the selection scope to pick which
 *   paragraph a drag position belongs to.
 * - Behavior focus: when a scope position falls inside a block's Y band, that block wins;
 *   otherwise the nearest block by vertical distance wins; an empty list yields null.
 */
class MemoTextSelectionHitTesterTest : UiComponentsFunSpec() {
    private val blocks =
        listOf(
            MemoTextSelectionBlockBounds(blockKey = "top", topPx = 0f, bottomPx = 40f, leftPx = 0f, rightPx = 200f),
            MemoTextSelectionBlockBounds(blockKey = "middle", topPx = 60f, bottomPx = 120f, leftPx = 0f, rightPx = 200f),
            MemoTextSelectionBlockBounds(blockKey = "bottom", topPx = 140f, bottomPx = 200f, leftPx = 0f, rightPx = 200f),
        )

    init {
        test("position inside a block's Y band picks that block") {
            (resolveMemoTextSelectionHitBlock(blocks, Offset(x = 50f, y = 80f))?.blockKey) shouldBe ("middle")
        }

        test("position in the gap snaps to the nearer block by Y distance") {
            // Above midpoint of 40..60 gap → closer to top.
            (resolveMemoTextSelectionHitBlock(blocks, Offset(x = 50f, y = 47f))?.blockKey) shouldBe ("top")
            // Below midpoint of 40..60 gap → closer to middle.
            (resolveMemoTextSelectionHitBlock(blocks, Offset(x = 50f, y = 58f))?.blockKey) shouldBe ("middle")
        }

        test("position far below the last block still picks the last block") {
            (resolveMemoTextSelectionHitBlock(blocks, Offset(x = 50f, y = 10_000f))?.blockKey) shouldBe ("bottom")
        }

        test("empty registry yields null") {
            (resolveMemoTextSelectionHitBlock(emptyList(), Offset(x = 0f, y = 0f))) shouldBe null
        }
    }
}
