package com.lomo.app.feature.memo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import java.util.ArrayDeque

/**
 * Full-document snapshots are retained per edit, so the stacks are bounded: once the limit is
 * exceeded the oldest entries are dropped. 100 steps keep long sessions useful while capping
 * retained history at O(document bytes x depth).
 */
internal const val UNDO_REDO_STACK_LIMIT = 100

internal class UndoRedoManager {
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    fun reset() {
        undoStack.clear()
        redoStack.clear()
        updateAvailability()
    }

    fun recordTextChange(
        previousValue: TextFieldValue,
        newValue: TextFieldValue,
    ) {
        if (previousValue.text == newValue.text) {
            return
        }
        undoStack.addLast(previousValue)
        trimToLimit(undoStack)
        redoStack.clear()
        updateAvailability()
    }

    fun undo(currentValue: TextFieldValue): TextFieldValue {
        val previousValue = undoStack.pollLast() ?: return currentValue
        redoStack.addLast(currentValue)
        trimToLimit(redoStack)
        updateAvailability()
        return previousValue
    }

    fun redo(currentValue: TextFieldValue): TextFieldValue {
        val nextValue = redoStack.pollLast() ?: return currentValue
        undoStack.addLast(currentValue)
        trimToLimit(undoStack)
        updateAvailability()
        return nextValue
    }

    private fun trimToLimit(stack: ArrayDeque<TextFieldValue>) {
        while (stack.size > UNDO_REDO_STACK_LIMIT) {
            stack.removeFirst()
        }
    }

    private fun updateAvailability() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }
}
