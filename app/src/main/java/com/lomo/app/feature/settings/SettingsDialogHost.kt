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
import com.lomo.app.feature.lanshare.LanSharePairingCodePolicy
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.WebDavProvider
import com.lomo.ui.component.dialog.SelectionDialog

data class SettingsDialogOptions(
    val dateFormats: List<String>,
    val timeFormats: List<String>,
    val themeModes: List<ThemeMode>,
    val filenameFormats: List<String>,
    val timestampFormats: List<String>,
    val gitSyncIntervals: List<String>,
    val webDavProviders: List<WebDavProvider>,
    val languageTag: String,
    val languageLabels: Map<String, String>,
    val themeModeLabels: Map<ThemeMode, String>,
    val gitSyncIntervalLabels: Map<String, String>,
    val webDavProviderLabels: Map<WebDavProvider, String>,
)

@Composable
fun SettingsDialogHost(
    uiState: SettingsScreenUiState,
    storageFeature: SettingsStorageFeatureViewModel,
    displayFeature: SettingsDisplayFeatureViewModel,
    lanShareFeature: SettingsLanShareFeatureViewModel,
    gitFeature: SettingsGitFeatureViewModel,
    webDavFeature: SettingsWebDavFeatureViewModel,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
    onApplyLanguageTag: (String) -> Unit,
    gitConflictSummary: String,
    gitDirectPathRequired: String,
    unknownErrorMessage: String,
) {
    if (dialogState.showDateDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_select_date_format),
            options = options.dateFormats,
            currentSelection = uiState.display.dateFormat,
            onDismiss = { dialogState.showDateDialog = false },
            onSelect = {
                displayFeature.updateDateFormat(it)
                dialogState.showDateDialog = false
            },
        )
    }

    if (dialogState.showTimeDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_select_time_format),
            options = options.timeFormats,
            currentSelection = uiState.display.timeFormat,
            onDismiss = { dialogState.showTimeDialog = false },
            onSelect = {
                displayFeature.updateTimeFormat(it)
                dialogState.showTimeDialog = false
            },
        )
    }

    if (dialogState.showThemeDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_select_theme),
            options = options.themeModes,
            currentSelection = uiState.display.themeMode,
            onDismiss = { dialogState.showThemeDialog = false },
            onSelect = {
                displayFeature.updateThemeMode(it)
                dialogState.showThemeDialog = false
            },
            labelProvider = { options.themeModeLabels[it] ?: it.value },
        )
    }

    if (dialogState.showLanguageDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_select_language),
            options = listOf("system", "zh-CN", "en"),
            currentSelection = options.languageTag,
            onDismiss = { dialogState.showLanguageDialog = false },
            onSelect = {
                onApplyLanguageTag(it)
                dialogState.showLanguageDialog = false
            },
            labelProvider = { options.languageLabels[it] ?: it },
        )
    }

    if (dialogState.showFilenameDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_select_filename_format),
            options = options.filenameFormats,
            currentSelection = uiState.storage.filenameFormat,
            onDismiss = { dialogState.showFilenameDialog = false },
            onSelect = {
                storageFeature.updateStorageFilenameFormat(it)
                dialogState.showFilenameDialog = false
            },
        )
    }

    if (dialogState.showTimestampDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_select_timestamp_format),
            options = options.timestampFormats,
            currentSelection = uiState.storage.timestampFormat,
            onDismiss = { dialogState.showTimestampDialog = false },
            onSelect = {
                storageFeature.updateStorageTimestampFormat(it)
                dialogState.showTimestampDialog = false
            },
        )
    }

    if (dialogState.showLanPairingDialog) {
        AlertDialog(
            onDismissRequest = {
                lanShareFeature.clearPairingCodeError()
                dialogState.showLanPairingDialog = false
            },
            title = { Text(stringResource(R.string.settings_lan_share_pairing_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_lan_share_pairing_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = dialogState.lanPairingCodeInput,
                        onValueChange = {
                            dialogState.lanPairingCodeInput = it
                            if (uiState.lanShare.pairingCodeError != null) {
                                lanShareFeature.clearPairingCodeError()
                            }
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_lan_share_pairing_hint)) },
                        visualTransformation =
                            if (dialogState.lanPairingCodeVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                        trailingIcon = {
                            TextButton(onClick = { dialogState.lanPairingCodeVisible = !dialogState.lanPairingCodeVisible }) {
                                Text(
                                    text =
                                        if (dialogState.lanPairingCodeVisible) {
                                            stringResource(R.string.share_password_hide)
                                        } else {
                                            stringResource(R.string.share_password_show)
                                        },
                                )
                            }
                        },
                        isError = uiState.lanShare.pairingCodeError != null,
                        supportingText =
                            uiState.lanShare.pairingCodeError?.let {
                                {
                                    Text(SettingsErrorPresenter.pairingCodeMessage(it))
                                }
                            },
                    )
                    if (uiState.lanShare.pairingConfigured) {
                        TextButton(
                            onClick = {
                                lanShareFeature.clearLanSharePairingCode()
                                dialogState.showLanPairingDialog = false
                            },
                        ) {
                            Text(stringResource(R.string.action_clear_pairing_code))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        lanShareFeature.updateLanSharePairingCode(dialogState.lanPairingCodeInput)
                        if (LanSharePairingCodePolicy.shouldDismissDialogAfterSave(dialogState.lanPairingCodeInput)) {
                            dialogState.showLanPairingDialog = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        lanShareFeature.clearPairingCodeError()
                        dialogState.showLanPairingDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (dialogState.showDeviceNameDialog) {
        AlertDialog(
            onDismissRequest = { dialogState.showDeviceNameDialog = false },
            title = { Text(stringResource(R.string.share_device_name_label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.share_device_name_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = dialogState.deviceNameInput,
                        onValueChange = { dialogState.deviceNameInput = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.share_device_name_label)) },
                    )
                    TextButton(
                        onClick = {
                            lanShareFeature.updateLanShareDeviceName("")
                            dialogState.showDeviceNameDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.share_device_name_use_system))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        lanShareFeature.updateLanShareDeviceName(dialogState.deviceNameInput)
                        dialogState.showDeviceNameDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogState.showDeviceNameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (dialogState.showGitRemoteUrlDialog) {
        val isUrlValid = gitFeature.isValidGitRemoteUrl(dialogState.gitRemoteUrlInput)
        AlertDialog(
            onDismissRequest = { dialogState.showGitRemoteUrlDialog = false },
            title = { Text(stringResource(R.string.settings_git_remote_url)) },
            text = {
                OutlinedTextField(
                    value = dialogState.gitRemoteUrlInput,
                    onValueChange = { dialogState.gitRemoteUrlInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_git_remote_url_hint)) },
                    isError = dialogState.gitRemoteUrlInput.isNotBlank() && !isUrlValid,
                    supportingText =
                        if (dialogState.gitRemoteUrlInput.isNotBlank() && !isUrlValid) {
                            { Text(stringResource(R.string.settings_git_remote_url_error_https_required)) }
                        } else {
                            null
                        },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        gitFeature.updateGitRemoteUrl(dialogState.gitRemoteUrlInput.trim())
                        dialogState.showGitRemoteUrlDialog = false
                    },
                    enabled = isUrlValid,
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogState.showGitRemoteUrlDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (dialogState.showGitPatDialog) {
        AlertDialog(
            onDismissRequest = { dialogState.showGitPatDialog = false },
            title = { Text(stringResource(R.string.settings_git_pat_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_git_pat_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = dialogState.gitPatInput,
                        onValueChange = { dialogState.gitPatInput = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_git_pat_hint)) },
                        visualTransformation =
                            if (dialogState.gitPatVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                        trailingIcon = {
                            TextButton(onClick = { dialogState.gitPatVisible = !dialogState.gitPatVisible }) {
                                Text(
                                    text =
                                        if (dialogState.gitPatVisible) {
                                            stringResource(R.string.share_password_hide)
                                        } else {
                                            stringResource(R.string.share_password_show)
                                        },
                                )
                            }
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        gitFeature.updateGitPat(dialogState.gitPatInput.trim())
                        dialogState.showGitPatDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogState.showGitPatDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (dialogState.showGitAuthorNameDialog) {
        AlertDialog(
            onDismissRequest = { dialogState.showGitAuthorNameDialog = false },
            title = { Text(stringResource(R.string.settings_git_author_name)) },
            text = {
                OutlinedTextField(
                    value = dialogState.gitAuthorNameInput,
                    onValueChange = { dialogState.gitAuthorNameInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_git_author_name_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        gitFeature.updateGitAuthorName(dialogState.gitAuthorNameInput.trim())
                        dialogState.showGitAuthorNameDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogState.showGitAuthorNameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (dialogState.showGitAuthorEmailDialog) {
        AlertDialog(
            onDismissRequest = { dialogState.showGitAuthorEmailDialog = false },
            title = { Text(stringResource(R.string.settings_git_author_email)) },
            text = {
                OutlinedTextField(
                    value = dialogState.gitAuthorEmailInput,
                    onValueChange = { dialogState.gitAuthorEmailInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_git_author_email_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        gitFeature.updateGitAuthorEmail(dialogState.gitAuthorEmailInput.trim())
                        dialogState.showGitAuthorEmailDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogState.showGitAuthorEmailDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (dialogState.showGitSyncIntervalDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_git_select_sync_interval),
            options = options.gitSyncIntervals,
            currentSelection = uiState.git.autoSyncInterval,
            onDismiss = { dialogState.showGitSyncIntervalDialog = false },
            onSelect = {
                gitFeature.updateGitAutoSyncInterval(it)
                dialogState.showGitSyncIntervalDialog = false
            },
            labelProvider = { options.gitSyncIntervalLabels[it] ?: it },
        )
    }

    if (dialogState.showGitResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { dialogState.showGitResetConfirmDialog = false },
            title = { Text(stringResource(R.string.settings_git_reset_repo_confirm_title)) },
            text = { Text(stringResource(R.string.settings_git_reset_repo_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        gitFeature.resetGitRepository()
                        dialogState.showGitResetConfirmDialog = false
                    },
                    enabled = !uiState.git.resetInProgress,
                ) {
                    Text(stringResource(R.string.action_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogState.showGitResetConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (dialogState.showGitConflictResolutionDialog) {
        AlertDialog(
            onDismissRequest = { dialogState.showGitConflictResolutionDialog = false },
            title = { Text(stringResource(R.string.settings_git_conflict_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_git_conflict_dialog_message,
                        gitFeature.presentGitSyncErrorMessage(
                            message = dialogState.gitConflictMessage,
                            conflictSummary = gitConflictSummary,
                            directPathRequired = gitDirectPathRequired,
                            unknownError = unknownErrorMessage,
                        ),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        gitFeature.resolveGitConflictUsingLocal()
                        dialogState.showGitConflictResolutionDialog = false
                    },
                ) {
                    Text(stringResource(R.string.settings_git_conflict_keep_local))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        gitFeature.resolveGitConflictUsingRemote()
                        dialogState.showGitConflictResolutionDialog = false
                    },
                ) {
                    Text(stringResource(R.string.settings_git_conflict_use_remote))
                }
            },
        )
    }

    if (dialogState.showWebDavProviderDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_webdav_select_provider),
            options = options.webDavProviders,
            currentSelection = uiState.webDav.provider,
            onDismiss = { dialogState.showWebDavProviderDialog = false },
            onSelect = {
                webDavFeature.updateProvider(it)
                dialogState.showWebDavProviderDialog = false
            },
            labelProvider = { options.webDavProviderLabels[it] ?: it.name },
        )
    }

    if (dialogState.showWebDavBaseUrlDialog) {
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

    if (dialogState.showWebDavEndpointUrlDialog) {
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

    if (dialogState.showWebDavUsernameDialog) {
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

    if (dialogState.showWebDavPasswordDialog) {
        val messageRes =
            when (uiState.webDav.provider) {
                WebDavProvider.NUTSTORE -> R.string.settings_webdav_password_dialog_message_nutstore
                WebDavProvider.NEXTCLOUD -> R.string.settings_webdav_password_dialog_message_nextcloud
                WebDavProvider.CUSTOM -> R.string.settings_webdav_password_dialog_message_custom
            }
        AlertDialog(
            onDismissRequest = { dialogState.showWebDavPasswordDialog = false },
            title = { Text(stringResource(R.string.settings_webdav_password_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(messageRes),
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
            },
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

    if (dialogState.showWebDavSyncIntervalDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_webdav_select_sync_interval),
            options = options.gitSyncIntervals,
            currentSelection = uiState.webDav.autoSyncInterval,
            onDismiss = { dialogState.showWebDavSyncIntervalDialog = false },
            onSelect = {
                webDavFeature.updateAutoSyncInterval(it)
                dialogState.showWebDavSyncIntervalDialog = false
            },
            labelProvider = { options.gitSyncIntervalLabels[it] ?: it },
        )
    }
}
