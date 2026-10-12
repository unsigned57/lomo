package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoEditorController undo/redo history policy.
 * - Capability: snapshotting text edits, walking backward and forward through history,
 *   clearing redo after a fresh edit, and bounding history depth so long editing sessions do
 *   not grow the snapshot stack without limit.
 *
 * Scenarios:
 * - Given a sequence of text edits, when undo and redo run, then inputValue text/selection and
 *   canUndo/canRedo traverse the snapshots and redo clears after a fresh edit.
 * - Given more edits than the 100-entry depth bound, when undoing, then the oldest snapshots
 *   are dropped and traversal stops at the bound instead of the session start.
 *
 * Observable outcomes: inputValue text/selection and canUndo/canRedo flags across undo/redo transitions.
 *
 * TDD proof:
 * - Fails before the fix because MemoEditorController exposes no undo/redo operations or history state.
 * - Bounded-history scenario fails before the fix because the undo stack was unbounded: every
 *   pushed snapshot stayed reachable, so undos walked all the way back to the session start.
 *
 * Excludes: Compose toolbar rendering, Android EditText platform undo integration, and IME behavior.
  * Test Change Justification:
 * - Reason category: new bounded-history behavior — the undo stack is capped at 100 snapshots.
 * - Old behavior/assertion being replaced: none removed; a scenario and contract lines were
 *   added for the depth bound.
 * - Why old assertion is no longer correct: the previous contract implied unbounded history;
 *   the controller now drops the oldest snapshots past the limit.
 * - Coverage preserved by: existing undo/redo scenarios are unchanged; the new test pins the
 *   100-entry bound and the stop-at-v20 outcome.
 * - Why this is not fitting the test to the implementation: the bound is a deliberate policy
 *   limit stated in the contract, not an accommodation of a code path.
*/
class MemoEditorUndoRedoPolicyTest : AppFunSpec() {
    init {
        test("undo and redo traverse text snapshots and clear redo after a fresh edit") {
            val controller = MemoEditorController()

            controller.openForCreate("one")
            controller.updateInputValue(TextFieldValue("one two", TextRange(7)))
            controller.updateInputValue(TextFieldValue("one two three", TextRange(13)))

            ((controller.canUndo)) shouldBe true
            ((controller.canRedo)) shouldBe false

            controller.undo()

            (controller.inputValue.text) shouldBe ("one two")
            (controller.inputValue.selection) shouldBe (TextRange(7))
            ((controller.canUndo)) shouldBe true
            ((controller.canRedo)) shouldBe true

            controller.undo()

            (controller.inputValue.text) shouldBe ("one")
            (controller.inputValue.selection) shouldBe (TextRange(3))
            ((controller.canUndo)) shouldBe false
            ((controller.canRedo)) shouldBe true

            controller.redo()

            (controller.inputValue.text) shouldBe ("one two")
            ((controller.canUndo)) shouldBe true
            ((controller.canRedo)) shouldBe true

            controller.updateInputValue(TextFieldValue("replacement", TextRange(11)))

            (controller.inputValue.text) shouldBe ("replacement")
            ((controller.canUndo)) shouldBe true
            ((controller.canRedo)) shouldBe false
        }

        test("undo history drops the oldest snapshots once the depth limit is exceeded") {
            val controller = MemoEditorController()

            controller.openForCreate("v0")
            // 120 edits push the previous values v0..v119 onto the undo stack.
            for (step in 1..120) {
                val value = "v$step"
                controller.updateInputValue(TextFieldValue(value, TextRange(value.length)))
            }

            var undoCount = 0
            while (controller.canUndo) {
                controller.undo()
                undoCount += 1
            }

            // The stack is bounded at 100 entries, so the twenty oldest snapshots (v0..v19)
            // were dropped and undo stops at v20 instead of walking back to v0.
            undoCount shouldBe 100
            (controller.inputValue.text) shouldBe ("v20")
            ((controller.canRedo)) shouldBe true
        }
    }

}
