package com.lomo.app.feature.settings

import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.model.ThemeMode

data class StorageSectionState(
    val rootDirectory: String,
    val imageDirectory: String,
    val voiceDirectory: String,
    val filenameFormat: String,
    val timestampFormat: String,
)

data class DisplaySectionState(
    val dateFormat: String,
    val timeFormat: String,
    val themeMode: ThemeMode,
)

data class LanShareSectionState(
    val e2eEnabled: Boolean,
    val pairingConfigured: Boolean,
    val deviceName: String,
    val pairingCodeError: String?,
)

data class ShareCardSectionState(
    val style: ShareCardStyle,
    val showTime: Boolean,
    val showBrand: Boolean,
)

data class GitSectionState(
    val enabled: Boolean,
    val remoteUrl: String,
    val patConfigured: Boolean,
    val authorName: String,
    val authorEmail: String,
    val autoSyncEnabled: Boolean,
    val autoSyncInterval: String,
    val syncOnRefreshEnabled: Boolean,
    val lastSyncTime: Long,
    val syncState: SyncEngineState,
    val connectionTestState: SettingsGitConnectionTestState,
    val resetInProgress: Boolean,
)

data class InteractionSectionState(
    val hapticEnabled: Boolean,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val appLockEnabled: Boolean,
)

data class SystemSectionState(
    val checkUpdatesOnStartup: Boolean,
)

data class SettingsScreenUiState(
    val storage: StorageSectionState,
    val display: DisplaySectionState,
    val lanShare: LanShareSectionState,
    val shareCard: ShareCardSectionState,
    val git: GitSectionState,
    val interaction: InteractionSectionState,
    val system: SystemSectionState,
    val operationError: String?,
)
