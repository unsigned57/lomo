package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.ui.component.input.InputEditorDisplayMode

fun MemoEditorController.appendMarkdownBlock(markdown: String) {
    updateInputValue(buildMarkdownBlockInsertionValue(inputValue, markdown))
}

/**
 * Places incoming external text (from Android share or text-selection process-text) into the
 * memo editor and makes the editor visible for editing.
 *
 * When closed, opens a create session seeded with [createDraftText] before inserting so an
 * existing unsaved draft is preserved instead of overwritten by autosave. When already open,
 * keeps the active create/edit session and inserts at the current cursor.
 */
internal fun MemoEditorController.receiveExternalText(text: String, createDraftText: String) {
    if (!isVisible) {
        openForCreate(createDraftText)
        appendMarkdownBlock(text)
        return
    }
    val wasPreview = displayMode == InputEditorDisplayMode.Preview
    if (wasPreview) {
        updateDisplayMode(InputEditorDisplayMode.Edit)
    }
    appendMarkdownBlock(text)
    if (!wasPreview) {
        ensureVisible()
    }
}

/** Capture before stopping the recorder: restoring the editor can move its IME selection. */
internal fun MemoEditorController.captureMarkdownInsertion(): (String) -> Unit {
    val captured = inputValue
    return { markdown ->
        // New text typed while media finalizes owns its current selection; never overwrite it.
        val target = if (inputValue.text == captured.text) captured else inputValue
        updateInputValue(buildMarkdownBlockInsertionValue(target, markdown))
    }
}

private fun buildMarkdownBlockInsertionValue(value: TextFieldValue, markdown: String): TextFieldValue {
    val currentText = value.text
    val selectionStart = value.selection.start.coerceIn(0, currentText.length)
    val selectionEnd = value.selection.end.coerceIn(0, currentText.length)
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)

    val prefix = currentText.substring(0, start)
    val suffix = currentText.substring(end)

    val needsLeadingNewline = start > 0 && prefix[start - 1] != '\n'
    val needsTrailingNewline = end < currentText.length && suffix[0] != '\n'

    val insertion = buildString {
        if (needsLeadingNewline) append('\n')
        append(markdown)
        if (needsTrailingNewline) append('\n')
    }

    val newText = prefix + insertion + suffix
    val newCursorPosition = start + insertion.length
    return TextFieldValue(newText, TextRange(newCursorPosition))
}

fun MemoEditorController.appendImagesMarkdown(paths: List<String>) {
    if (paths.isEmpty()) return
    val markdown = paths.joinToString("\n") { path -> "![image]($path)" }
    appendMarkdownBlock(markdown = markdown)
}

fun MemoEditorController.appendImageMarkdown(path: String) {
    appendImagesMarkdown(listOf(path))
}
