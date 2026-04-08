package com.lomo.app.feature.settings

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
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun S3SelectionDialogs(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    val s3Feature = features.s3
    SelectionDialogIfVisible(
        visible = dialogState.showS3PathStyleDialog,
        title = stringResource(R.string.settings_s3_select_path_style),
        options = options.s3PathStyles.toImmutableList(),
        currentSelection = uiState.s3.pathStyle,
        onDismiss = { dialogState.showS3PathStyleDialog = false },
        onSelect = {
            s3Feature.updatePathStyle(it)
            dialogState.showS3PathStyleDialog = false
        },
        labelProvider = { options.s3PathStyleLabels[it] ?: it.name },
    )
    SelectionDialogIfVisible(
        visible = dialogState.showS3EncryptionModeDialog,
        title = stringResource(R.string.settings_s3_select_encryption_mode),
        options = options.s3EncryptionModes.toImmutableList(),
        currentSelection = uiState.s3.encryptionMode,
        onDismiss = { dialogState.showS3EncryptionModeDialog = false },
        onSelect = {
            s3Feature.updateEncryptionMode(it)
            dialogState.showS3EncryptionModeDialog = false
        },
        labelProvider = { options.s3EncryptionModeLabels[it] ?: it.name },
    )
    S3EncryptionPassword2Dialog(
        s3Feature = s3Feature,
        dialogState = dialogState,
    )
    SelectionDialogIfVisible(
        visible = dialogState.showS3RcloneFilenameEncryptionDialog,
        title = stringResource(R.string.settings_s3_select_rclone_filename_encryption),
        options = options.s3RcloneFilenameEncryptions.toImmutableList(),
        currentSelection = uiState.s3.rcloneFilenameEncryption,
        onDismiss = { dialogState.showS3RcloneFilenameEncryptionDialog = false },
        onSelect = {
            s3Feature.updateRcloneFilenameEncryption(it)
            dialogState.showS3RcloneFilenameEncryptionDialog = false
        },
        labelProvider = { options.s3RcloneFilenameEncryptionLabels[it] ?: it.name },
    )
    SelectionDialogIfVisible(
        visible = dialogState.showS3RcloneFilenameEncodingDialog,
        title = stringResource(R.string.settings_s3_select_rclone_filename_encoding),
        options = options.s3RcloneFilenameEncodings.toImmutableList(),
        currentSelection = uiState.s3.rcloneFilenameEncoding,
        onDismiss = { dialogState.showS3RcloneFilenameEncodingDialog = false },
        onSelect = {
            s3Feature.updateRcloneFilenameEncoding(it)
            dialogState.showS3RcloneFilenameEncodingDialog = false
        },
        labelProvider = { options.s3RcloneFilenameEncodingLabels[it] ?: it.name },
    )
    S3RcloneEncryptedSuffixDialog(
        s3Feature = s3Feature,
        dialogState = dialogState,
    )
    SelectionDialogIfVisible(
        visible = dialogState.showS3SyncIntervalDialog,
        title = stringResource(R.string.settings_s3_select_sync_interval),
        options = options.gitSyncIntervals.toImmutableList(),
        currentSelection = uiState.s3.autoSyncInterval,
        onDismiss = { dialogState.showS3SyncIntervalDialog = false },
        onSelect = {
            s3Feature.updateAutoSyncInterval(it)
            dialogState.showS3SyncIntervalDialog = false
        },
        labelProvider = { options.gitSyncIntervalLabels[it] ?: it },
    )
}

@Composable
internal fun S3EncryptionPassword2Dialog(
    s3Feature: SettingsS3FeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showS3EncryptionPassword2Dialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showS3EncryptionPassword2Dialog = false },
        title = { Text(stringResource(R.string.settings_s3_encryption_password2)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_s3_encryption_password2_message),
                    style = MaterialTheme.typography.bodySmall,
                )
                SecretFieldDialogContent(
                    value = dialogState.s3EncryptionPassword2Input,
                    onValueChange = { dialogState.s3EncryptionPassword2Input = it },
                    visible = dialogState.s3EncryptionPassword2Visible,
                    onToggleVisibility = {
                        dialogState.s3EncryptionPassword2Visible = !dialogState.s3EncryptionPassword2Visible
                    },
                    label = stringResource(R.string.settings_s3_encryption_password2_hint),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    s3Feature.updateEncryptionPassword2(dialogState.s3EncryptionPassword2Input.trim())
                    dialogState.showS3EncryptionPassword2Dialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showS3EncryptionPassword2Dialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun S3RcloneEncryptedSuffixDialog(
    s3Feature: SettingsS3FeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showS3RcloneEncryptedSuffixDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showS3RcloneEncryptedSuffixDialog = false },
        title = { Text(stringResource(R.string.settings_s3_rclone_encrypted_suffix)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_s3_rclone_encrypted_suffix_message),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = dialogState.s3RcloneEncryptedSuffixInput,
                    onValueChange = { dialogState.s3RcloneEncryptedSuffixInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_s3_rclone_encrypted_suffix_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    s3Feature.updateRcloneEncryptedSuffix(dialogState.s3RcloneEncryptedSuffixInput.trim())
                    dialogState.showS3RcloneEncryptedSuffixDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showS3RcloneEncryptedSuffixDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun SecretFieldDialogContent(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    label: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            label = { Text(label) },
            visualTransformation =
                if (visible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
        )
        TextButton(onClick = onToggleVisibility) {
            Text(
                stringResource(
                    if (visible) {
                        R.string.settings_hide_password
                    } else {
                        R.string.settings_show_password
                    },
                ),
            )
        }
    }
}
