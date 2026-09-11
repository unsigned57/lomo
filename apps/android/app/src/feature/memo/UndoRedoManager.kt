package com.lomo.app.feature.memo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import java.util.ArrayDeque

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
        redoStack.clear()
        updateAvailability()
    }

    fun undo(currentValue: TextFieldValue): TextFieldValue {
        val previousValue = undoStack.pollLast() ?: return currentValue
        redoStack.addLast(currentValue)
        updateAvailability()
        return previousValue
    }

    fun redo(currentValue: TextFieldValue): TextFieldValue {
        val nextValue = redoStack.pollLast() ?: return currentValue
        undoStack.addLast(currentValue)
        updateAvailability()
        return nextValue
    }

    private fun updateAvailability() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }
}
