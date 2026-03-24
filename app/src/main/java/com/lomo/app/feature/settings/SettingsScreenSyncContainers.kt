package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import com.lomo.app.feature.lanshare.LanSharePairingDialogTriggerPolicy
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncState

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

private fun SettingsDialogState.openLanPairingDialog(
    lanShareFeature: SettingsLanShareFeatureViewModel,
) {
    lanPairingCodeInput = ""
    lanPairingCodeVisible = false
    lanShareFeature.clearPairingCodeError()
    showLanPairingDialog = true
}

private fun SettingsDialogState.openGitPatDialog() {
    gitPatInput = ""
    gitPatVisible = false
    showGitPatDialog = true
}

private fun SettingsDialogState.openWebDavPasswordDialog() {
    webDavPasswordInput = ""
    webDavPasswordVisible = false
    showWebDavPasswordDialog = true
}

private fun SyncEngineState.canTriggerManualSync(): Boolean =
    this !is SyncEngineState.Syncing && this !is SyncEngineState.Initializing

private fun WebDavSyncState.canTriggerManualSync(): Boolean =
    when (this) {
        WebDavSyncState.Connecting,
        WebDavSyncState.Listing,
        WebDavSyncState.Uploading,
        WebDavSyncState.Downloading,
        WebDavSyncState.Deleting,
        WebDavSyncState.Initializing,
        -> false

        else -> true
    }
