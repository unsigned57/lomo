package com.lomo.ui.component.input

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.jetbrains.compose.resources.stringResource
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.*

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
        title = { Text(stringResource(Res.string.input_discard_title)) },
        text = { Text(stringResource(Res.string.input_discard_message)) },
        confirmButton = {
            TextButton(onClick = onConfirmDiscard) {
                Text(stringResource(Res.string.input_discard_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.input_discard_cancel))
            }
        },
    )
}
