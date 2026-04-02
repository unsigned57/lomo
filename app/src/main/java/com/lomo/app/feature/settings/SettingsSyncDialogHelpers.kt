package com.lomo.app.feature.settings

import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.model.WebDavSyncState

internal fun SettingsDialogState.openLanPairingDialog(
    lanShareFeature: SettingsLanShareFeatureViewModel,
) {
    lanPairingCodeInput = ""
    lanPairingCodeVisible = false
    lanShareFeature.clearPairingCodeError()
    showLanPairingDialog = true
}

internal fun SettingsDialogState.openGitPatDialog() {
    gitPatInput = ""
    gitPatVisible = false
    showGitPatDialog = true
}

internal fun SettingsDialogState.openWebDavPasswordDialog() {
    webDavPasswordInput = ""
    webDavPasswordVisible = false
    showWebDavPasswordDialog = true
}

internal fun SettingsDialogState.openS3SecretAccessKeyDialog() {
    s3SecretAccessKeyInput = ""
    s3SecretAccessKeyVisible = false
    showS3SecretAccessKeyDialog = true
}

internal fun SettingsDialogState.openS3SessionTokenDialog() {
    s3SessionTokenInput = ""
    s3SessionTokenVisible = false
    showS3SessionTokenDialog = true
}

internal fun SettingsDialogState.openS3EncryptionPasswordDialog() {
    s3EncryptionPasswordInput = ""
    s3EncryptionPasswordVisible = false
    showS3EncryptionPasswordDialog = true
}

internal fun SyncEngineState.canTriggerManualSync(): Boolean =
    this !is SyncEngineState.Syncing && this !is SyncEngineState.Initializing

internal fun WebDavSyncState.canTriggerManualSync(): Boolean =
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

internal fun S3SyncState.canTriggerManualSync(): Boolean =
    when (this) {
        S3SyncState.Connecting,
        S3SyncState.Listing,
        S3SyncState.Uploading,
        S3SyncState.Downloading,
        S3SyncState.Deleting,
        S3SyncState.Initializing,
        -> false

        else -> true
    }
