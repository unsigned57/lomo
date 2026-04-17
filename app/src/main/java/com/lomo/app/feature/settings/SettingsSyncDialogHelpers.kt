package com.lomo.app.feature.settings

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

internal fun SettingsDialogState.openS3EncryptionPassword2Dialog() {
    s3EncryptionPassword2Input = ""
    s3EncryptionPassword2Visible = false
    showS3EncryptionPassword2Dialog = true
}
