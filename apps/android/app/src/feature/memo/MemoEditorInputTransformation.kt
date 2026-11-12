package com.lomo.app.feature.memo

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.ui.component.input.applyInputHiddenTextEdit
import com.lomo.ui.component.input.mergeInputHiddenRanges

/** Applies only buffer edits: no outside state mutation or asynchronous value acknowledgement. */
internal fun memoEditorInputTransformation(sourceText: String, hiddenRanges: List<TextRange>): InputTransformation =
    InputTransformation {
        val previous = TextFieldValue(originalText.toString(), originalSelection)
        val proposed = TextFieldValue(asCharSequence().toString(), selection)
        val currentRanges = if (previous.text == sourceText) mergeInputHiddenRanges(hiddenRanges) else emptyList()
        val atomic = applyInputHiddenTextEdit(previous, proposed, currentRanges)
        val continued = autoContinueListMarker(previous, atomic) ?: atomic
        if (continued.text != proposed.text) replace(0, length, continued.text)
        if (continued.selection != selection) selection = continued.selection
    }
