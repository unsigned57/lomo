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
import com.lomo.domain.model.WebDavProvider
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun WebDavDialogs(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    val webDavFeature = features.webDav
    SelectionDialogIfVisible(
        visible = dialogState.showWebDavProviderDialog,
        title = stringResource(R.string.settings_webdav_select_provider),
        options = options.webDavProviders.toImmutableList(),
        currentSelection = uiState.webDav.provider,
        onDismiss = { dialogState.showWebDavProviderDialog = false },
        onSelect = {
            webDavFeature.updateProvider(it)
            dialogState.showWebDavProviderDialog = false
        },
        labelProvider = { options.webDavProviderLabels[it] ?: it.name },
    )
    WebDavBaseUrlDialog(
        webDavFeature = webDavFeature,
        dialogState = dialogState,
    )
    WebDavEndpointUrlDialog(
        webDavFeature = webDavFeature,
        dialogState = dialogState,
    )
    WebDavUsernameDialog(
        webDavFeature = webDavFeature,
        dialogState = dialogState,
    )
    WebDavPasswordDialog(
        uiState = uiState,
        webDavFeature = webDavFeature,
        dialogState = dialogState,
    )
    SelectionDialogIfVisible(
        visible = dialogState.showWebDavSyncIntervalDialog,
        title = stringResource(R.string.settings_webdav_select_sync_interval),
        options = options.gitSyncIntervals.toImmutableList(),
        currentSelection = uiState.webDav.autoSyncInterval,
        onDismiss = { dialogState.showWebDavSyncIntervalDialog = false },
        onSelect = {
            webDavFeature.updateAutoSyncInterval(it)
            dialogState.showWebDavSyncIntervalDialog = false
        },
        labelProvider = { options.gitSyncIntervalLabels[it] ?: it },
    )
}

@Composable
private fun WebDavBaseUrlDialog(
    webDavFeature: SettingsWebDavFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showWebDavBaseUrlDialog) {
        return
    }
    val isUrlValid = webDavFeature.isValidWebDavUrl(dialogState.webDavBaseUrlInput)
    AlertDialog(
        onDismissRequest = { dialogState.showWebDavBaseUrlDialog = false },
        title = { Text(stringResource(R.string.settings_webdav_base_url)) },
        text = {
            OutlinedTextField(
                value = dialogState.webDavBaseUrlInput,
                onValueChange = { dialogState.webDavBaseUrlInput = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_webdav_base_url_hint)) },
                isError = dialogState.webDavBaseUrlInput.isNotBlank() && !isUrlValid,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    webDavFeature.updateBaseUrl(dialogState.webDavBaseUrlInput.trim())
                    dialogState.showWebDavBaseUrlDialog = false
                },
                enabled = isUrlValid,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showWebDavBaseUrlDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun WebDavEndpointUrlDialog(
    webDavFeature: SettingsWebDavFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showWebDavEndpointUrlDialog) {
        return
    }
    val isUrlValid = webDavFeature.isValidWebDavUrl(dialogState.webDavEndpointUrlInput)
    AlertDialog(
        onDismissRequest = { dialogState.showWebDavEndpointUrlDialog = false },
        title = { Text(stringResource(R.string.settings_webdav_endpoint_url)) },
        text = {
            OutlinedTextField(
                value = dialogState.webDavEndpointUrlInput,
                onValueChange = { dialogState.webDavEndpointUrlInput = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_webdav_endpoint_url_hint)) },
                isError = dialogState.webDavEndpointUrlInput.isNotBlank() && !isUrlValid,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    webDavFeature.updateEndpointUrl(dialogState.webDavEndpointUrlInput.trim())
                    dialogState.showWebDavEndpointUrlDialog = false
                },
                enabled = isUrlValid,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showWebDavEndpointUrlDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun WebDavUsernameDialog(
    webDavFeature: SettingsWebDavFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showWebDavUsernameDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showWebDavUsernameDialog = false },
        title = { Text(stringResource(R.string.settings_webdav_username)) },
        text = {
            OutlinedTextField(
                value = dialogState.webDavUsernameInput,
                onValueChange = { dialogState.webDavUsernameInput = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_webdav_username_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    webDavFeature.updateUsername(dialogState.webDavUsernameInput.trim())
                    dialogState.showWebDavUsernameDialog = false
                },
                enabled = dialogState.webDavUsernameInput.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showWebDavUsernameDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun WebDavPasswordDialog(
    uiState: SettingsScreenUiState,
    webDavFeature: SettingsWebDavFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showWebDavPasswordDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showWebDavPasswordDialog = false },
        title = { Text(stringResource(R.string.settings_webdav_password_dialog_title)) },
        text = { WebDavPasswordDialogContent(uiState = uiState, dialogState = dialogState) },
        confirmButton = {
            TextButton(
                onClick = {
                    webDavFeature.updatePassword(dialogState.webDavPasswordInput.trim())
                    dialogState.showWebDavPasswordDialog = false
                },
                enabled = dialogState.webDavPasswordInput.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showWebDavPasswordDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun WebDavPasswordDialogContent(
    uiState: SettingsScreenUiState,
    dialogState: SettingsDialogState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(webDavPasswordMessageRes(uiState.webDav.provider)),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = dialogState.webDavPasswordInput,
            onValueChange = { dialogState.webDavPasswordInput = it },
            singleLine = true,
            label = { Text(stringResource(R.string.settings_webdav_password_hint)) },
            visualTransformation =
                if (dialogState.webDavPasswordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
        )
        TextButton(onClick = { dialogState.webDavPasswordVisible = !dialogState.webDavPasswordVisible }) {
            Text(
                stringResource(
                    if (dialogState.webDavPasswordVisible) {
                        R.string.settings_hide_password
                    } else {
                        R.string.settings_show_password
                    },
                ),
            )
        }
    }
}

private fun webDavPasswordMessageRes(provider: WebDavProvider): Int =
    when (provider) {
        WebDavProvider.NUTSTORE -> R.string.settings_webdav_password_dialog_message_nutstore
        WebDavProvider.NEXTCLOUD -> R.string.settings_webdav_password_dialog_message_nextcloud
        WebDavProvider.CUSTOM -> R.string.settings_webdav_password_dialog_message_custom
    }
