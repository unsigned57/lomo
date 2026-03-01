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
    var showShareCardStyleDialog by mutableStateOf(false)

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
    var gitConflictMessage by mutableStateOf("")
}

@Composable
fun rememberSettingsDialogState(): SettingsDialogState = remember { SettingsDialogState() }
