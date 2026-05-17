package com.lomo.ui.text

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: resolveMemoParagraphDoubleTapOutcome — the decision function that
 *   turns a double-tap on a memo paragraph into either "open editor (optionally clearing
 *   the active selection first)" or "ignore".
 *
 * Scenario matrix:
 * - Happy: double-tap with double click handler opens editor.
 * - Boundary: double-tap when another paragraph holds selection clears it first.
 * - Failure: double-tap without double click handler returns Ignore.
 * - Must-not-happen: double-tap never opens editor when handler is absent.
 *
 * Behavior focus: free-copy selection and the quick-edit double-tap are independent
 *   gestures. A double-tap must open the editor regardless of whether a selection is
 *   currently active. When a selection is active, the outcome clears it first so the
 *   editor pane doesn't open on top of dangling selection chrome. When no double-click
 *   handler is wired, double-tap is a no-op (matches the legacy contract).
 * - Observable outcomes: returned MemoParagraphDoubleTapOutcome for each combination of
 *   (hasDoubleClickHandler, paragraphHasSelection, scopeHasSelection).
 * - Red phase: Fails before the fix because the live decision lives inline as
 *   "skip double-tap whenever any selection is live", and the pure decision function this
 *   test pins does not exist yet.
 * - Excludes: editor opening mechanics, haptic feedback, selection clearing side effects.
 */
class MemoParagraphDoubleTapOutcomeTest : UiComponentsFunSpec() {
    init {
        test("double-tap without any selection opens the editor without clearing") {
            val outcome =
                resolveMemoParagraphDoubleTapOutcome(
                    hasDoubleClickHandler = true,
                    paragraphHasSelection = false,
                    scopeHasSelection = false,
                )

            outcome shouldBe MemoParagraphDoubleTapOutcome.OpenEditor(clearSelectionFirst = false)
        }

        test("double-tap on the paragraph that owns the selection opens the editor and clears the selection") {
            val outcome =
                resolveMemoParagraphDoubleTapOutcome(
                    hasDoubleClickHandler = true,
                    paragraphHasSelection = true,
                    scopeHasSelection = true,
                )

            outcome shouldBe MemoParagraphDoubleTapOutcome.OpenEditor(clearSelectionFirst = true)
        }

        test("double-tap on a paragraph not in the selection still opens the editor and clears scope selection") {
            val outcome =
                resolveMemoParagraphDoubleTapOutcome(
                    hasDoubleClickHandler = true,
                    paragraphHasSelection = false,
                    scopeHasSelection = true,
                )

            outcome shouldBe MemoParagraphDoubleTapOutcome.OpenEditor(clearSelectionFirst = true)
        }

        test("double-tap with no editor wired is ignored regardless of selection state") {
            val outcomeIdle =
                resolveMemoParagraphDoubleTapOutcome(
                    hasDoubleClickHandler = false,
                    paragraphHasSelection = false,
                    scopeHasSelection = false,
                )
            val outcomeWithSelection =
                resolveMemoParagraphDoubleTapOutcome(
                    hasDoubleClickHandler = false,
                    paragraphHasSelection = false,
                    scopeHasSelection = true,
                )

            outcomeIdle shouldBe MemoParagraphDoubleTapOutcome.Ignore
            outcomeWithSelection shouldBe MemoParagraphDoubleTapOutcome.Ignore
        }
    }
}
