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
    openProviderTextDialog(RemoteProviderTextField.GitPat, initialValue = "")
}

internal fun SettingsDialogState.openWebDavPasswordDialog() {
    openProviderTextDialog(RemoteProviderTextField.WebDavPassword, initialValue = "")
}

internal fun SettingsDialogState.openS3SecretAccessKeyDialog() {
    openProviderTextDialog(RemoteProviderTextField.S3SecretAccessKey, initialValue = "")
}

internal fun SettingsDialogState.openS3SessionTokenDialog() {
    openProviderTextDialog(RemoteProviderTextField.S3SessionToken, initialValue = "")
}

internal fun SettingsDialogState.openS3EncryptionPasswordDialog() {
    openProviderTextDialog(RemoteProviderTextField.S3EncryptionPassword, initialValue = "")
}

internal fun SettingsDialogState.openS3EncryptionPassword2Dialog() {
    openProviderTextDialog(RemoteProviderTextField.S3EncryptionPassword2, initialValue = "")
}
