package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: MemoEditorController undo/redo history policy.
 * - Behavior focus: snapshotting text edits, walking backward and forward through history, and clearing redo after a fresh edit.
 * - Observable outcomes: inputValue text/selection and canUndo/canRedo flags across undo/redo transitions.
 * - Red phase: Fails before the fix because MemoEditorController exposes no undo/redo operations or history state.
 * - Excludes: Compose toolbar rendering, Android EditText platform undo integration, and IME behavior.
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
    }

}
