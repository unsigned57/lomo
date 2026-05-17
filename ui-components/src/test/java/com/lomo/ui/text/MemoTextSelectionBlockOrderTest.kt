package com.lomo.ui.text

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: resolveMemoBlockOrderByY — the function that turns a set of registered
 *   selection blocks into a forward-traversal order used by MemoMultiParagraphSelection.
 *
 * Scenario matrix:
 * - Happy: blocks are sorted strictly by their Y position.
 * - Boundary: stable sort is preserved when Y coordinates are equal.
 * - Failure: blocks missing Y measurements fall back gracefully after measured blocks.
 * - Must-not-happen: an empty block registration list never fails and returns an empty list.
 *
 * Behavior focus: cross-paragraph selections rely on this order to decide which block is
 *   "first", "middle", or "last"; rendering a multi-block selection on an expanded memo
 *   depends on the order matching the on-screen vertical layout. Registration order
 *   (LinkedHashMap insertion order from onGloballyPositioned dispatches) is **not**
 *   guaranteed by Compose to match layout order, so the registrar must sort by each
 *   block's resolved Y position. Blocks without a known Y fall back to their registration
 *   index so they retain a stable spot.
 * - Observable outcomes: the returned key list for a (registrationOrder, yByKey) pair.
 * - Red phase: Fails before the fix because the registrar returns insertion order verbatim
 *   and middle blocks of a backward-registered run end up outside the
 *   `targetIndex in startIndex..endIndex` window, which makes selectedRangeForBlock return
 *   null for them and erases their highlight.
 * - Excludes: how Y is actually measured against LayoutCoordinates (covered by the registrar
 *   wiring once this pure function exists).
 */
class MemoTextSelectionBlockOrderTest : UiComponentsFunSpec() {
    init {
        test("blocks sort by Y position regardless of registration order") {
            val ordered =
                resolveMemoBlockOrderByY(
                    registrationOrder = listOf<Any>("third", "first", "second"),
                    yByKey = mapOf<Any, Float>("first" to 0f, "second" to 100f, "third" to 200f),
                )

            ordered shouldBe listOf<Any>("first", "second", "third")
        }

        test("blocks with the same Y retain their registration order (stable sort)") {
            val ordered =
                resolveMemoBlockOrderByY(
                    registrationOrder = listOf<Any>("a", "b", "c"),
                    yByKey = mapOf<Any, Float>("a" to 10f, "b" to 10f, "c" to 10f),
                )

            ordered shouldBe listOf<Any>("a", "b", "c")
        }

        test("blocks missing a Y measurement fall back to their registration index after measured blocks") {
            val ordered =
                resolveMemoBlockOrderByY(
                    registrationOrder = listOf<Any>("measured-late", "unmeasured", "measured-early"),
                    yByKey = mapOf<Any, Float>("measured-early" to 0f, "measured-late" to 200f),
                )

            ordered shouldBe listOf<Any>("measured-early", "measured-late", "unmeasured")
        }

        test("an empty registration produces an empty order") {
            val ordered =
                resolveMemoBlockOrderByY(
                    registrationOrder = emptyList(),
                    yByKey = emptyMap(),
                )

            ordered shouldBe emptyList<Any>()
        }
    }
}
