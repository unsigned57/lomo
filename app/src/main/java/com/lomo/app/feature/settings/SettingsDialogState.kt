package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Stable
class SettingsDialogState {
    var showDateDialog by mutableStateOf(false)
    var showTimeDialog by mutableStateOf(false)
    var showThemeDialog by mutableStateOf(false)
    var showFilenameDialog by mutableStateOf(false)
    var showTimestampDialog by mutableStateOf(false)
    var showLanguageDialog by mutableStateOf(false)
    var showShareCardSignatureDialog by mutableStateOf(false)
    var shareCardSignatureInput by mutableStateOf("")
    var showMemoSnapshotCountDialog by mutableStateOf(false)
    var showMemoSnapshotAgeDialog by mutableStateOf(false)
    var pendingSnapshotDisableTarget by mutableStateOf<SettingsSnapshotDisableTarget?>(null)

    var showLanPairingDialog by mutableStateOf(false)
    var lanPairingCodeInput by mutableStateOf("")
    var lanPairingCodeVisible by mutableStateOf(false)
    var showDeviceNameDialog by mutableStateOf(false)
    var deviceNameInput by mutableStateOf("")

    var showGitRemoteUrlDialog by mutableStateOf(false)
    var gitRemoteUrlInput by mutableStateOf("")
    var showGitPatDialog by mutableStateOf(false)
    var gitPatInput by mutableStateOf("")
    var gitPatVisible by mutableStateOf(false)
    var showGitAuthorNameDialog by mutableStateOf(false)
    var gitAuthorNameInput by mutableStateOf("")
    var showGitAuthorEmailDialog by mutableStateOf(false)
    var gitAuthorEmailInput by mutableStateOf("")
    var showGitSyncIntervalDialog by mutableStateOf(false)
    var showGitResetConfirmDialog by mutableStateOf(false)
    var showGitConflictResolutionDialog by mutableStateOf(false)
    var gitConflictError by mutableStateOf<SettingsOperationError.GitSync?>(null)

    var showWebDavProviderDialog by mutableStateOf(false)
    var showWebDavBaseUrlDialog by mutableStateOf(false)
    var webDavBaseUrlInput by mutableStateOf("")
    var showWebDavEndpointUrlDialog by mutableStateOf(false)
    var webDavEndpointUrlInput by mutableStateOf("")
    var showWebDavUsernameDialog by mutableStateOf(false)
    var webDavUsernameInput by mutableStateOf("")
    var showWebDavPasswordDialog by mutableStateOf(false)
    var webDavPasswordInput by mutableStateOf("")
    var webDavPasswordVisible by mutableStateOf(false)
    var showWebDavSyncIntervalDialog by mutableStateOf(false)

    var showS3EndpointUrlDialog by mutableStateOf(false)
    var s3EndpointUrlInput by mutableStateOf("")
    var showS3RegionDialog by mutableStateOf(false)
    var s3RegionInput by mutableStateOf("")
    var showS3BucketDialog by mutableStateOf(false)
    var s3BucketInput by mutableStateOf("")
    var showS3PrefixDialog by mutableStateOf(false)
    var s3PrefixInput by mutableStateOf("")
    var showS3AccessKeyIdDialog by mutableStateOf(false)
    var s3AccessKeyIdInput by mutableStateOf("")
    var showS3SecretAccessKeyDialog by mutableStateOf(false)
    var s3SecretAccessKeyInput by mutableStateOf("")
    var s3SecretAccessKeyVisible by mutableStateOf(false)
    var showS3SessionTokenDialog by mutableStateOf(false)
    var s3SessionTokenInput by mutableStateOf("")
    var s3SessionTokenVisible by mutableStateOf(false)
    var showS3PathStyleDialog by mutableStateOf(false)
    var showS3EncryptionModeDialog by mutableStateOf(false)
    var showS3EncryptionPasswordDialog by mutableStateOf(false)
    var s3EncryptionPasswordInput by mutableStateOf("")
    var s3EncryptionPasswordVisible by mutableStateOf(false)
    var showS3EncryptionPassword2Dialog by mutableStateOf(false)
    var s3EncryptionPassword2Input by mutableStateOf("")
    var s3EncryptionPassword2Visible by mutableStateOf(false)
    var showS3RcloneFilenameEncryptionDialog by mutableStateOf(false)
    var showS3RcloneFilenameEncodingDialog by mutableStateOf(false)
    var showS3RcloneEncryptedSuffixDialog by mutableStateOf(false)
    var s3RcloneEncryptedSuffixInput by mutableStateOf("")
    var showS3SyncIntervalDialog by mutableStateOf(false)
}

@Composable
fun rememberSettingsDialogState(): SettingsDialogState = remember { SettingsDialogState() }

enum class SettingsSnapshotDisableTarget {
    MEMO,
}
