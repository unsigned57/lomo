package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import com.lomo.app.feature.lanshare.LanSharePairingDialogTriggerPolicy
import com.lomo.domain.model.WebDavProvider

@Composable
internal fun LanShareSettingsSectionContainer(
    state: LanShareSectionState,
    dialogState: SettingsDialogState,
    lanShareFeature: SettingsLanShareFeatureViewModel,
) {
    LanShareSettingsSection(
        state = state,
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
    gitSyncIntervalLabels: Map<String, String>,
) {
    GitSyncSettingsSection(
        state = state,
        syncIntervalLabel = gitSyncIntervalLabels[state.autoSyncInterval] ?: state.autoSyncInterval,
        syncNowSubtitle =
            SettingsErrorPresenter.gitSyncNowSubtitle(
                state = state.syncState,
                lastSyncTime = state.lastSyncTime,
            ),
        connectionSubtitle = connectionTestSubtitle(state.connectionTestState),
        onToggleEnabled = gitFeature.updateGitSyncEnabled,
        onOpenRemoteUrlDialog = {
            dialogState.gitRemoteUrlInput = state.remoteUrl
            dialogState.showGitRemoteUrlDialog = true
        },
        onOpenPatDialog = dialogState::openGitPatDialog,
        onOpenAuthorNameDialog = {
            dialogState.gitAuthorNameInput = state.authorName
            dialogState.showGitAuthorNameDialog = true
        },
        onOpenAuthorEmailDialog = {
            dialogState.gitAuthorEmailInput = state.authorEmail
            dialogState.showGitAuthorEmailDialog = true
        },
        onToggleAutoSync = gitFeature.updateGitAutoSyncEnabled,
        onOpenSyncIntervalDialog = { dialogState.showGitSyncIntervalDialog = true },
        onToggleSyncOnRefresh = gitFeature.updateGitSyncOnRefresh,
        onSyncNow = { if (state.syncState.canTriggerManualSync()) gitFeature.triggerGitSyncNow() },
        onTestConnection = {
            gitFeature.resetConnectionTestState()
            gitFeature.testGitConnection()
        },
        onOpenResetDialog = { dialogState.showGitResetConfirmDialog = true },
    )
}

@Composable
internal fun WebDavSyncSettingsSectionContainer(
    state: WebDavSectionState,
    dialogState: SettingsDialogState,
    webDavFeature: SettingsWebDavFeatureViewModel,
    gitSyncIntervalLabels: Map<String, String>,
    webDavProviderLabels: Map<WebDavProvider, String>,
) {
    WebDavSyncSettingsSection(
        state = state,
        providerLabel = webDavProviderLabels[state.provider] ?: state.provider.name,
        syncIntervalLabel = gitSyncIntervalLabels[state.autoSyncInterval] ?: state.autoSyncInterval,
        syncNowSubtitle =
            SettingsErrorPresenter.webDavSyncNowSubtitle(
                state = state.syncState,
                lastSyncTime = state.lastSyncTime,
            ),
        connectionSubtitle = connectionTestSubtitle(state.connectionTestState),
        onToggleEnabled = webDavFeature.updateWebDavSyncEnabled,
        onOpenProviderDialog = { dialogState.showWebDavProviderDialog = true },
        onOpenBaseUrlDialog = {
            dialogState.webDavBaseUrlInput = state.baseUrl
            dialogState.showWebDavBaseUrlDialog = true
        },
        onOpenEndpointUrlDialog = {
            dialogState.webDavEndpointUrlInput = state.endpointUrl
            dialogState.showWebDavEndpointUrlDialog = true
        },
        onOpenUsernameDialog = {
            dialogState.webDavUsernameInput = state.username
            dialogState.showWebDavUsernameDialog = true
        },
        onOpenPasswordDialog = dialogState::openWebDavPasswordDialog,
        onToggleAutoSync = webDavFeature.updateAutoSyncEnabled,
        onOpenSyncIntervalDialog = { dialogState.showWebDavSyncIntervalDialog = true },
        onToggleSyncOnRefresh = webDavFeature.updateSyncOnRefresh,
        onSyncNow = { if (state.syncState.canTriggerManualSync()) webDavFeature.triggerSyncNow() },
        onTestConnection = {
            webDavFeature.resetConnectionTestState()
            webDavFeature.testConnection()
        },
    )
}

@Composable
internal fun S3SyncSettingsSectionContainer(
    state: S3SectionState,
    dialogState: SettingsDialogState,
    s3Feature: SettingsS3FeatureViewModel,
    onSelectLocalSyncDirectory: () -> Unit,
    syncIntervalLabels: Map<String, String>,
    pathStyleLabels: Map<com.lomo.domain.model.S3PathStyle, String>,
    encryptionModeLabels: Map<com.lomo.domain.model.S3EncryptionMode, String>,
) {
    S3SyncSettingsSection(
        state = state,
        pathStyleLabel = pathStyleLabels[state.pathStyle] ?: state.pathStyle.name,
        encryptionModeLabel = encryptionModeLabels[state.encryptionMode] ?: state.encryptionMode.name,
        syncIntervalLabel = syncIntervalLabels[state.autoSyncInterval] ?: state.autoSyncInterval,
        syncNowSubtitle =
            SettingsErrorPresenter.s3SyncNowSubtitle(
                state = state.syncState,
                lastSyncTime = state.lastSyncTime,
            ),
        connectionSubtitle = s3ConnectionTestSubtitle(state.connectionTestState),
        onToggleEnabled = s3Feature.updateS3SyncEnabled,
        onOpenEndpointUrlDialog = {
            dialogState.s3EndpointUrlInput = state.endpointUrl
            dialogState.showS3EndpointUrlDialog = true
        },
        onOpenRegionDialog = {
            dialogState.s3RegionInput = state.region
            dialogState.showS3RegionDialog = true
        },
        onOpenBucketDialog = {
            dialogState.s3BucketInput = state.bucket
            dialogState.showS3BucketDialog = true
        },
        onOpenPrefixDialog = {
            dialogState.s3PrefixInput = state.prefix
            dialogState.showS3PrefixDialog = true
        },
        onSelectLocalSyncDirectory = onSelectLocalSyncDirectory,
        onClearLocalSyncDirectory = s3Feature.clearLocalSyncDirectory,
        onOpenAccessKeyIdDialog = {
            dialogState.s3AccessKeyIdInput = ""
            dialogState.showS3AccessKeyIdDialog = true
        },
        onOpenSecretAccessKeyDialog = dialogState::openS3SecretAccessKeyDialog,
        onOpenSessionTokenDialog = dialogState::openS3SessionTokenDialog,
        onOpenPathStyleDialog = { dialogState.showS3PathStyleDialog = true },
        onOpenEncryptionModeDialog = { dialogState.showS3EncryptionModeDialog = true },
        onOpenEncryptionPasswordDialog = dialogState::openS3EncryptionPasswordDialog,
        onToggleAutoSync = s3Feature.updateAutoSyncEnabled,
        onOpenSyncIntervalDialog = { dialogState.showS3SyncIntervalDialog = true },
        onToggleSyncOnRefresh = s3Feature.updateSyncOnRefresh,
        onSyncNow = { if (state.syncState.canTriggerManualSync()) s3Feature.triggerSyncNow() },
        onTestConnection = {
            s3Feature.resetConnectionTestState()
            s3Feature.testConnection()
        },
    )
}
