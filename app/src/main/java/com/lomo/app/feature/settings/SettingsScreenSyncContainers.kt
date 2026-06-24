package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lomo.app.feature.lanshare.LanSharePairingDialogTriggerPolicy
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.WebDavProvider
import kotlinx.collections.immutable.ImmutableMap

@Composable
internal fun LanShareSettingsSectionContainer(
    state: LanShareSectionState,
    dialogState: SettingsDialogState,
    lanShareFeature: SettingsLanShareFeatureViewModel,
) {
    LanShareSettingsSection(
        state = state,
        onToggleEnabled = lanShareFeature::updateLanShareEnabled,
        onToggleE2e = { enabled ->
            lanShareFeature.updateLanShareE2eEnabled(enabled)
            if (LanSharePairingDialogTriggerPolicy.shouldShowOnE2eEnabled(enabled, state.pairingConfigured)) {
                dialogState.openLanPairingDialog(lanShareFeature)
            }
        },
        onOpenPairingDialog = { dialogState.openLanPairingDialog(lanShareFeature) },
        onOpenDeviceNameDialog = {
            dialogState.deviceNameInput = state.deviceName
            dialogState.showDeviceNameDialog = true
        },
    )
}

@Composable
internal fun GitSyncSettingsSectionContainer(
    state: GitSectionState,
    dialogState: SettingsDialogState,
    gitFeature: SettingsGitFeatureViewModel,
    gitSyncIntervalLabels: ImmutableMap<String, String>,
) {
    val providerSettings = state.providerSettings
    GitSyncSettingsSection(
        state = state,
        labels =
            RemoteProviderSectionLabels(
                syncInterval =
                    gitSyncIntervalLabels[providerSettings.autoSyncInterval]
                        ?: providerSettings.autoSyncInterval,
            ),
        dialogs =
            GitSyncDialogActions(
                openRemoteUrl =
                    dialogState.providerTextDialogAction(RemoteProviderTextField.GitRemoteUrl) {
                        state.remoteUrl
                    },
                openPat = dialogState.providerTextDialogAction(RemoteProviderTextField.GitPat),
                openAuthorName =
                    dialogState.providerTextDialogAction(RemoteProviderTextField.GitAuthorName) {
                        state.authorName
                    },
                openAuthorEmail =
                    dialogState.providerTextDialogAction(RemoteProviderTextField.GitAuthorEmail) {
                        state.authorEmail
                    },
                openReset =
                    dialogState.providerConfirmationDialogAction(
                        RemoteProviderConfirmationAction.GitResetRepository,
                    ),
            ),
        actions =
            remoteProviderSectionActions(
                provider = SyncBackendType.GIT,
                providerSettings = providerSettings,
                providerFeature = gitFeature.provider,
                openSyncInterval =
                    dialogState.providerSelectionDialogAction(RemoteProviderSelectionField.GitSyncInterval),
            ),
        modifier = Modifier,
    )
}

@Composable
internal fun WebDavSyncSettingsSectionContainer(
    state: WebDavSectionState,
    dialogState: SettingsDialogState,
    webDavFeature: SettingsWebDavFeatureViewModel,
    gitSyncIntervalLabels: ImmutableMap<String, String>,
    webDavProviderLabels: ImmutableMap<WebDavProvider, String>,
) {
    val providerSettings = state.providerSettings
    WebDavSyncSettingsSection(
        state = state,
        labels =
            WebDavSyncSectionLabels(
                common =
                    RemoteProviderSectionLabels(
                        syncInterval =
                            gitSyncIntervalLabels[providerSettings.autoSyncInterval]
                                ?: providerSettings.autoSyncInterval,
                    ),
                provider = webDavProviderLabels[state.provider] ?: state.provider.name,
            ),
        dialogs =
            WebDavSyncDialogActions(
                openProvider =
                    dialogState.providerSelectionDialogAction(RemoteProviderSelectionField.WebDavProvider),
                openBaseUrl =
                    dialogState.providerTextDialogAction(RemoteProviderTextField.WebDavBaseUrl) {
                        state.baseUrl
                    },
                openEndpointUrl =
                    dialogState.providerTextDialogAction(RemoteProviderTextField.WebDavEndpointUrl) {
                        state.endpointUrl
                    },
                openUsername =
                    dialogState.providerTextDialogAction(RemoteProviderTextField.WebDavUsername) {
                        state.username
                    },
                openPassword = dialogState.providerTextDialogAction(RemoteProviderTextField.WebDavPassword),
            ),
        actions =
            remoteProviderSectionActions(
                provider = SyncBackendType.WEBDAV,
                providerSettings = providerSettings,
                providerFeature = webDavFeature.provider,
                openSyncInterval =
                    dialogState.providerSelectionDialogAction(RemoteProviderSelectionField.WebDavSyncInterval),
            ),
        modifier = Modifier,
    )
}

@Composable
internal fun S3SyncSettingsSectionContainer(
    state: S3SectionState,
    dialogState: SettingsDialogState,
    s3Feature: SettingsS3FeatureViewModel,
    onSelectLocalSyncDirectory: () -> Unit,
    labelSources: S3SyncLabelSources,
) {
    val providerSettings = state.providerSettings
    S3SyncSettingsSection(
        state = state,
        labels = s3SyncSectionLabels(state = state, sources = labelSources),
        dialogs =
            s3SyncDialogActions(
                state = state,
                dialogState = dialogState,
                onSelectLocalSyncDirectory = onSelectLocalSyncDirectory,
                onClearLocalSyncDirectory = s3Feature.clearLocalSyncDirectory,
            ),
        specificActions =
            S3SyncSpecificActions(
                toggleRcloneDirectoryNameEncryption = s3Feature.updateRcloneDirectoryNameEncryption,
                toggleRcloneDataEncryptionEnabled = s3Feature.updateRcloneDataEncryptionEnabled,
            ),
        actions =
            remoteProviderSectionActions(
                provider = SyncBackendType.S3,
                providerSettings = providerSettings,
                providerFeature = s3Feature.provider,
                openSyncInterval =
                    dialogState.providerSelectionDialogAction(RemoteProviderSelectionField.S3SyncInterval),
            ),
        modifier = Modifier,
    )
}

@Composable
private fun s3SyncSectionLabels(
    state: S3SectionState,
    sources: S3SyncLabelSources,
): S3SyncSectionLabels =
    S3SyncSectionLabels(
        common =
            RemoteProviderSectionLabels(
                syncInterval =
                    sources.syncIntervals[state.providerSettings.autoSyncInterval]
                        ?: state.providerSettings.autoSyncInterval,
            ),
        pathStyle = sources.pathStyles[state.pathStyle] ?: state.pathStyle.name,
        encryptionMode = sources.encryptionModes[state.encryptionMode] ?: state.encryptionMode.name,
        rcloneFilenameEncryption =
            sources.rcloneFilenameEncryptions[state.rcloneFilenameEncryption]
                ?: state.rcloneFilenameEncryption.name,
        rcloneFilenameEncoding =
            sources.rcloneFilenameEncodings[state.rcloneFilenameEncoding] ?: state.rcloneFilenameEncoding.name,
    )

private fun remoteProviderSectionActions(
    provider: SyncBackendType,
    providerSettings: RemoteProviderSettingsModel,
    providerFeature: SettingsRemoteProviderFeatureViewModel,
    openSyncInterval: () -> Unit,
): RemoteProviderSectionActions =
    RemoteProviderSectionActions(
        provider = provider,
        toggleEnabled = providerFeature::updateEnabled,
        toggleAutoSync = providerFeature::updateAutoSyncEnabled,
        openSyncInterval = openSyncInterval,
        toggleSyncOnRefresh = providerFeature::updateSyncOnRefresh,
        syncNow = {
            if (providerSettings.syncState.canTriggerManualSync()) {
                providerFeature.triggerSyncNow()
            }
        },
        testConnection = {
            providerFeature.resetConnectionTestState()
            providerFeature.testConnection()
        },
    )

private fun s3SyncDialogActions(
    state: S3SectionState,
    dialogState: SettingsDialogState,
    onSelectLocalSyncDirectory: () -> Unit,
    onClearLocalSyncDirectory: () -> Unit,
): S3SyncDialogActions =
    S3SyncDialogActions(
        endpoint =
            S3EndpointDialogActions(
                openEndpointUrl =
                    dialogState.providerTextDialogAction(RemoteProviderTextField.S3EndpointUrl) {
                        state.endpointUrl
                    },
                openRegion =
                    dialogState.providerTextDialogAction(RemoteProviderTextField.S3Region) {
                        state.region
                    },
                openBucket =
                    dialogState.providerTextDialogAction(RemoteProviderTextField.S3Bucket) {
                        state.bucket
                    },
                openPrefix =
                    dialogState.providerTextDialogAction(RemoteProviderTextField.S3Prefix) {
                        state.prefix
                    },
                selectLocalSyncDirectory = onSelectLocalSyncDirectory,
                clearLocalSyncDirectory = onClearLocalSyncDirectory,
            ),
        credentials =
            S3CredentialDialogActions(
                openAccessKeyId = dialogState.providerTextDialogAction(RemoteProviderTextField.S3AccessKeyId),
                openSecretAccessKey =
                    dialogState.providerTextDialogAction(RemoteProviderTextField.S3SecretAccessKey),
                openSessionToken = dialogState.providerTextDialogAction(RemoteProviderTextField.S3SessionToken),
            ),
        encryption =
            S3EncryptionDialogActions(
                openPathStyle = dialogState.providerSelectionDialogAction(RemoteProviderSelectionField.S3PathStyle),
                openEncryptionMode =
                    dialogState.providerSelectionDialogAction(RemoteProviderSelectionField.S3EncryptionMode),
                openEncryptionPassword =
                    dialogState.providerTextDialogAction(RemoteProviderTextField.S3EncryptionPassword),
                openEncryptionPassword2 =
                    dialogState.providerTextDialogAction(RemoteProviderTextField.S3EncryptionPassword2),
                openRcloneFilenameEncryption =
                    dialogState.providerSelectionDialogAction(
                        RemoteProviderSelectionField.S3RcloneFilenameEncryption,
                    ),
                openRcloneFilenameEncoding =
                    dialogState.providerSelectionDialogAction(RemoteProviderSelectionField.S3RcloneFilenameEncoding),
                openRcloneEncryptedSuffix =
                    dialogState.providerTextDialogAction(
                        RemoteProviderTextField.S3RcloneEncryptedSuffix,
                    ) { state.rcloneEncryptedSuffix },
            ),
    )
