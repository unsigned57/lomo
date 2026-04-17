package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoEditorController undo/redo history policy.
 * - Behavior focus: snapshotting text edits, walking backward and forward through history, and clearing redo after a fresh edit.
 * - Observable outcomes: inputValue text/selection and canUndo/canRedo flags across undo/redo transitions.
 * - Red phase: Fails before the fix because MemoEditorController exposes no undo/redo operations or history state.
 * - Excludes: Compose toolbar rendering, Android EditText platform undo integration, and IME behavior.
 */
class MemoEditorUndoRedoPolicyTest {
    @Test
    fun `undo and redo traverse text snapshots and clear redo after a fresh edit`() {
        val controller = MemoEditorController()

        controller.openForCreate("one")
        controller.updateInputValue(TextFieldValue("one two", TextRange(7)))
        controller.updateInputValue(TextFieldValue("one two three", TextRange(13)))

        assertTrue(controller.canUndo)
        assertFalse(controller.canRedo)

        controller.undo()

        assertEquals("one two", controller.inputValue.text)
        assertEquals(TextRange(7), controller.inputValue.selection)
        assertTrue(controller.canUndo)
        assertTrue(controller.canRedo)

        controller.undo()

        assertEquals("one", controller.inputValue.text)
        assertEquals(TextRange(3), controller.inputValue.selection)
        assertFalse(controller.canUndo)
        assertTrue(controller.canRedo)

        controller.redo()

        assertEquals("one two", controller.inputValue.text)
        assertTrue(controller.canUndo)
        assertTrue(controller.canRedo)

        controller.updateInputValue(TextFieldValue("replacement", TextRange(11)))

        assertEquals("replacement", controller.inputValue.text)
        assertTrue(controller.canUndo)
        assertFalse(controller.canRedo)
    }
}
