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
import androidx.compose.ui.unit.dp
import com.lomo.app.R

@Composable
internal fun S3Dialogs(
    uiState: SettingsScreenUiState,
    s3Feature: SettingsS3FeatureViewModel,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    S3EndpointUrlDialog(
        s3Feature = s3Feature,
        dialogState = dialogState,
    )
    S3RegionDialog(
        s3Feature = s3Feature,
        dialogState = dialogState,
    )
    S3BucketDialog(
        s3Feature = s3Feature,
        dialogState = dialogState,
    )
    S3PrefixDialog(
        s3Feature = s3Feature,
        dialogState = dialogState,
    )
    S3AccessKeyIdDialog(
        s3Feature = s3Feature,
        dialogState = dialogState,
    )
    S3SecretAccessKeyDialog(
        s3Feature = s3Feature,
        dialogState = dialogState,
    )
    S3SessionTokenDialog(
        s3Feature = s3Feature,
        dialogState = dialogState,
    )
    S3EncryptionPasswordDialog(
        s3Feature = s3Feature,
        dialogState = dialogState,
    )
    S3SelectionDialogs(
        uiState = uiState,
        s3Feature = s3Feature,
        dialogState = dialogState,
        options = options,
    )
}

@Composable
private fun S3EndpointUrlDialog(
    s3Feature: SettingsS3FeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showS3EndpointUrlDialog) {
        return
    }
    val isUrlValid =
        dialogState.s3EndpointUrlInput.isBlank() || s3Feature.isValidEndpointUrl(dialogState.s3EndpointUrlInput)
    AlertDialog(
        onDismissRequest = { dialogState.showS3EndpointUrlDialog = false },
        title = { Text(stringResource(R.string.settings_s3_endpoint_url)) },
        text = {
            OutlinedTextField(
                value = dialogState.s3EndpointUrlInput,
                onValueChange = { dialogState.s3EndpointUrlInput = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_s3_endpoint_url_hint)) },
                isError = dialogState.s3EndpointUrlInput.isNotBlank() && !isUrlValid,
                supportingText =
                    if (dialogState.s3EndpointUrlInput.isNotBlank() && !isUrlValid) {
                        { Text(stringResource(R.string.settings_git_remote_url_error_https_required)) }
                    } else {
                        null
                    },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    s3Feature.updateEndpointUrl(dialogState.s3EndpointUrlInput.trim())
                    dialogState.showS3EndpointUrlDialog = false
                },
                enabled = isUrlValid,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showS3EndpointUrlDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun S3RegionDialog(
    s3Feature: SettingsS3FeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showS3RegionDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showS3RegionDialog = false },
        title = { Text(stringResource(R.string.settings_s3_region)) },
        text = {
            OutlinedTextField(
                value = dialogState.s3RegionInput,
                onValueChange = { dialogState.s3RegionInput = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_s3_region_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    s3Feature.updateRegion(dialogState.s3RegionInput.trim())
                    dialogState.showS3RegionDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showS3RegionDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun S3BucketDialog(
    s3Feature: SettingsS3FeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showS3BucketDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showS3BucketDialog = false },
        title = { Text(stringResource(R.string.settings_s3_bucket)) },
        text = {
            OutlinedTextField(
                value = dialogState.s3BucketInput,
                onValueChange = { dialogState.s3BucketInput = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_s3_bucket_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    s3Feature.updateBucket(dialogState.s3BucketInput.trim())
                    dialogState.showS3BucketDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showS3BucketDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun S3PrefixDialog(
    s3Feature: SettingsS3FeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showS3PrefixDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showS3PrefixDialog = false },
        title = { Text(stringResource(R.string.settings_s3_prefix)) },
        text = {
            OutlinedTextField(
                value = dialogState.s3PrefixInput,
                onValueChange = { dialogState.s3PrefixInput = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_s3_prefix_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    s3Feature.updatePrefix(dialogState.s3PrefixInput.trim())
                    dialogState.showS3PrefixDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showS3PrefixDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun S3AccessKeyIdDialog(
    s3Feature: SettingsS3FeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showS3AccessKeyIdDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showS3AccessKeyIdDialog = false },
        title = { Text(stringResource(R.string.settings_s3_access_key_id)) },
        text = {
            OutlinedTextField(
                value = dialogState.s3AccessKeyIdInput,
                onValueChange = { dialogState.s3AccessKeyIdInput = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_s3_access_key_id_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    s3Feature.updateAccessKeyId(dialogState.s3AccessKeyIdInput.trim())
                    dialogState.showS3AccessKeyIdDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showS3AccessKeyIdDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun S3SecretAccessKeyDialog(
    s3Feature: SettingsS3FeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showS3SecretAccessKeyDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showS3SecretAccessKeyDialog = false },
        title = { Text(stringResource(R.string.settings_s3_secret_access_key)) },
        text = {
            SecretFieldDialogContent(
                value = dialogState.s3SecretAccessKeyInput,
                onValueChange = { dialogState.s3SecretAccessKeyInput = it },
                visible = dialogState.s3SecretAccessKeyVisible,
                onToggleVisibility = { dialogState.s3SecretAccessKeyVisible = !dialogState.s3SecretAccessKeyVisible },
                label = stringResource(R.string.settings_s3_secret_access_key_hint),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    s3Feature.updateSecretAccessKey(dialogState.s3SecretAccessKeyInput.trim())
                    dialogState.showS3SecretAccessKeyDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showS3SecretAccessKeyDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun S3SessionTokenDialog(
    s3Feature: SettingsS3FeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showS3SessionTokenDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showS3SessionTokenDialog = false },
        title = { Text(stringResource(R.string.settings_s3_session_token)) },
        text = {
            SecretFieldDialogContent(
                value = dialogState.s3SessionTokenInput,
                onValueChange = { dialogState.s3SessionTokenInput = it },
                visible = dialogState.s3SessionTokenVisible,
                onToggleVisibility = { dialogState.s3SessionTokenVisible = !dialogState.s3SessionTokenVisible },
                label = stringResource(R.string.settings_s3_session_token_hint),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    s3Feature.updateSessionToken(dialogState.s3SessionTokenInput.trim())
                    dialogState.showS3SessionTokenDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showS3SessionTokenDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun S3EncryptionPasswordDialog(
    s3Feature: SettingsS3FeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showS3EncryptionPasswordDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showS3EncryptionPasswordDialog = false },
        title = { Text(stringResource(R.string.settings_s3_encryption_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_s3_encryption_password_message),
                    style = MaterialTheme.typography.bodySmall,
                )
                SecretFieldDialogContent(
                    value = dialogState.s3EncryptionPasswordInput,
                    onValueChange = { dialogState.s3EncryptionPasswordInput = it },
                    visible = dialogState.s3EncryptionPasswordVisible,
                    onToggleVisibility = {
                        dialogState.s3EncryptionPasswordVisible = !dialogState.s3EncryptionPasswordVisible
                    },
                    label = stringResource(R.string.settings_s3_encryption_password_hint),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    s3Feature.updateEncryptionPassword(dialogState.s3EncryptionPasswordInput.trim())
                    dialogState.showS3EncryptionPasswordDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showS3EncryptionPasswordDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
