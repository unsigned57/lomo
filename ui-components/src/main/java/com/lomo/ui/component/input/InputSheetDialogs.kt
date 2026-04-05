package com.lomo.ui.component.input

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.lomo.ui.R

@Composable
internal fun InputSheetSubmissionResetEffect(
    inputText: String,
    isSubmitting: Boolean,
    submissionLockSourceText: String?,
    onClearSubmissionLock: () -> Unit,
) {
    LaunchedEffect(inputText, isSubmitting, submissionLockSourceText) {
        val sourceText = submissionLockSourceText ?: return@LaunchedEffect
        if (isSubmitting && inputText != sourceText) {
            onClearSubmissionLock()
        }
    }
}

@Composable
internal fun InputDiscardDialog(
    onDismiss: () -> Unit,
    onConfirmDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.input_discard_title)) },
        text = { Text(stringResource(R.string.input_discard_message)) },
        confirmButton = {
            TextButton(onClick = onConfirmDiscard) {
                Text(stringResource(R.string.input_discard_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.input_discard_cancel))
            }
        },
    )
}
