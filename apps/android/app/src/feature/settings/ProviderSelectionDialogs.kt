package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun ProviderSelectionDialog(
    route: SettingsDialogRoute.RemoteProviderSelection,
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    when (route.field) {
        RemoteProviderSelectionField.GitSyncInterval ->
            GitSyncIntervalSelectionDialog(uiState, features, dialogState, options)
        RemoteProviderSelectionField.WebDavProvider ->
            WebDavProviderSelectionDialog(uiState, features, dialogState, options)
        RemoteProviderSelectionField.WebDavSyncInterval ->
            WebDavSyncIntervalSelectionDialog(uiState, features, dialogState, options)
        RemoteProviderSelectionField.S3PathStyle ->
            S3PathStyleSelectionDialog(uiState, features, dialogState, options)
        RemoteProviderSelectionField.S3EncryptionMode ->
            S3EncryptionModeSelectionDialog(uiState, features, dialogState, options)
        RemoteProviderSelectionField.S3RcloneFilenameEncryption ->
            S3RcloneFilenameEncryptionSelectionDialog(uiState, features, dialogState, options)
        RemoteProviderSelectionField.S3RcloneFilenameEncoding ->
            S3RcloneFilenameEncodingSelectionDialog(uiState, features, dialogState, options)
        RemoteProviderSelectionField.S3SyncInterval ->
            S3SyncIntervalSelectionDialog(uiState, features, dialogState, options)
    }
}

@Composable
private fun GitSyncIntervalSelectionDialog(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    SelectionDialogIfVisible(
        visible = true,
        title = stringResource(R.string.settings_git_select_sync_interval),
        options = options.gitSyncIntervals.toImmutableList(),
        currentSelection = uiState.git.providerSettings.autoSyncInterval,
        onDismiss = dialogState::dismissProviderDialog,
        onSelect = { selection ->
            features.git.provider.updateAutoSyncInterval(selection)
            dialogState.dismissProviderDialog()
        },
        labelProvider = { options.gitSyncIntervalLabels[it] ?: it },
    )
}

@Composable
private fun WebDavProviderSelectionDialog(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    SelectionDialogIfVisible(
        visible = true,
        title = stringResource(R.string.settings_webdav_select_provider),
        options = options.webDavProviders.toImmutableList(),
        currentSelection = uiState.webDav.provider,
        onDismiss = dialogState::dismissProviderDialog,
        onSelect = { selection ->
            features.webDav.updateProvider(selection)
            dialogState.dismissProviderDialog()
        },
        labelProvider = { options.webDavProviderLabels[it] ?: it.name },
    )
}

@Composable
private fun WebDavSyncIntervalSelectionDialog(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    SelectionDialogIfVisible(
        visible = true,
        title = stringResource(R.string.settings_webdav_select_sync_interval),
        options = options.gitSyncIntervals.toImmutableList(),
        currentSelection = uiState.webDav.providerSettings.autoSyncInterval,
        onDismiss = dialogState::dismissProviderDialog,
        onSelect = { selection ->
            features.webDav.provider.updateAutoSyncInterval(selection)
            dialogState.dismissProviderDialog()
        },
        labelProvider = { options.gitSyncIntervalLabels[it] ?: it },
    )
}

@Composable
private fun S3PathStyleSelectionDialog(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    SelectionDialogIfVisible(
        visible = true,
        title = stringResource(R.string.settings_s3_select_path_style),
        options = options.s3PathStyles.toImmutableList(),
        currentSelection = uiState.s3.pathStyle,
        onDismiss = dialogState::dismissProviderDialog,
        onSelect = { selection ->
            features.s3.updatePathStyle(selection)
            dialogState.dismissProviderDialog()
        },
        labelProvider = { options.s3PathStyleLabels[it] ?: it.name },
    )
}

@Composable
private fun S3EncryptionModeSelectionDialog(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    SelectionDialogIfVisible(
        visible = true,
        title = stringResource(R.string.settings_s3_select_encryption_mode),
        options = options.s3EncryptionModes.toImmutableList(),
        currentSelection = uiState.s3.encryptionMode,
        onDismiss = dialogState::dismissProviderDialog,
        onSelect = { selection ->
            features.s3.updateEncryptionMode(selection)
            dialogState.dismissProviderDialog()
        },
        labelProvider = { options.s3EncryptionModeLabels[it] ?: it.name },
    )
}

@Composable
private fun S3RcloneFilenameEncryptionSelectionDialog(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    SelectionDialogIfVisible(
        visible = true,
        title = stringResource(R.string.settings_s3_select_rclone_filename_encryption),
        options = options.s3RcloneFilenameEncryptions.toImmutableList(),
        currentSelection = uiState.s3.rcloneFilenameEncryption,
        onDismiss = dialogState::dismissProviderDialog,
        onSelect = { selection ->
            features.s3.updateRcloneFilenameEncryption(selection)
            dialogState.dismissProviderDialog()
        },
        labelProvider = { options.s3RcloneFilenameEncryptionLabels[it] ?: it.name },
    )
}

@Composable
private fun S3RcloneFilenameEncodingSelectionDialog(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    SelectionDialogIfVisible(
        visible = true,
        title = stringResource(R.string.settings_s3_select_rclone_filename_encoding),
        options = options.s3RcloneFilenameEncodings.toImmutableList(),
        currentSelection = uiState.s3.rcloneFilenameEncoding,
        onDismiss = dialogState::dismissProviderDialog,
        onSelect = { selection ->
            features.s3.updateRcloneFilenameEncoding(selection)
            dialogState.dismissProviderDialog()
        },
        labelProvider = { options.s3RcloneFilenameEncodingLabels[it] ?: it.name },
    )
}

@Composable
private fun S3SyncIntervalSelectionDialog(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    SelectionDialogIfVisible(
        visible = true,
        title = stringResource(R.string.settings_s3_select_sync_interval),
        options = options.gitSyncIntervals.toImmutableList(),
        currentSelection = uiState.s3.providerSettings.autoSyncInterval,
        onDismiss = dialogState::dismissProviderDialog,
        onSelect = { selection ->
            features.s3.provider.updateAutoSyncInterval(selection)
            dialogState.dismissProviderDialog()
        },
        labelProvider = { options.gitSyncIntervalLabels[it] ?: it },
    )
}
