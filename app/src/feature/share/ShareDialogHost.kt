package com.lomo.app.feature.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.lomo.app.R
import com.lomo.ui.theme.AppSpacing

@Composable
fun ShareDialogHost(
    visible: Boolean,
    pairingCodeInput: String,
    pairingCodeVisible: Boolean,
    pairingCodeError: String?,
    pairingConfigured: Boolean,
    isTechnicalMessage: (String) -> Boolean,
    onDismiss: () -> Unit,
    onPairingCodeInputChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onClearPairingCode: () -> Unit,
    onSave: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_e2e_password_dialog_title)) },
        text = {
            ShareDialogContent(
                pairingCodeInput = pairingCodeInput,
                pairingCodeVisible = pairingCodeVisible,
                pairingCodeError = pairingCodeError,
                pairingConfigured = pairingConfigured,
                isTechnicalMessage = isTechnicalMessage,
                onPairingCodeInputChange = onPairingCodeInputChange,
                onToggleVisibility = onToggleVisibility,
                onClearPairingCode = onClearPairingCode,
            )
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ShareDialogContent(
    pairingCodeInput: String,
    pairingCodeVisible: Boolean,
    pairingCodeError: String?,
    pairingConfigured: Boolean,
    isTechnicalMessage: (String) -> Boolean,
    onPairingCodeInputChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onClearPairingCode: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Text(
            text = stringResource(R.string.share_e2e_password_dialog_message),
            style = MaterialTheme.typography.bodyMedium,
        )
        PairingCodeField(
            pairingCodeInput = pairingCodeInput,
            pairingCodeVisible = pairingCodeVisible,
            pairingCodeError = pairingCodeError,
            isTechnicalMessage = isTechnicalMessage,
            onPairingCodeInputChange = onPairingCodeInputChange,
            onToggleVisibility = onToggleVisibility,
        )
        if (pairingConfigured) {
            TextButton(onClick = onClearPairingCode) {
                Text(stringResource(R.string.action_clear_pairing_code))
            }
        }
    }
}

@Composable
private fun PairingCodeField(
    pairingCodeInput: String,
    pairingCodeVisible: Boolean,
    pairingCodeError: String?,
    isTechnicalMessage: (String) -> Boolean,
    onPairingCodeInputChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
) {
    OutlinedTextField(
        value = pairingCodeInput,
        onValueChange = onPairingCodeInputChange,
        label = { Text(stringResource(R.string.share_e2e_password_hint)) },
        singleLine = true,
        visualTransformation =
            if (pairingCodeVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        trailingIcon = {
            TextButton(onClick = onToggleVisibility) {
                Text(
                    if (pairingCodeVisible) {
                        stringResource(R.string.share_password_hide)
                    } else {
                        stringResource(R.string.share_password_show)
                    },
                )
            }
        },
        isError = pairingCodeError != null,
        supportingText =
            pairingCodeError?.let {
                { Text(ShareErrorPresenter.detail(it, isTechnicalMessage)) }
            },
    )
}
