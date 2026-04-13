package com.lomo.app.feature.settings

import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncState

sealed interface DirectoryDisplayState {
    data object Loading : DirectoryDisplayState

    data class Resolved(
        val value: String?,
    ) : DirectoryDisplayState
}

internal fun DirectoryDisplayState.subtitle(notSetLabel: String): String =
    when (this) {
        DirectoryDisplayState.Loading -> ""
        is DirectoryDisplayState.Resolved -> value?.takeIf(String::isNotBlank) ?: notSetLabel
    }

data class StorageSectionState(
    val rootDirectory: DirectoryDisplayState,
    val imageDirectory: DirectoryDisplayState,
    val voiceDirectory: DirectoryDisplayState,
    val syncInboxEnabled: Boolean,
    val syncInboxDirectory: DirectoryDisplayState,
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
    val showTime: Boolean,
    val showBrand: Boolean,
)

data class SnapshotSectionState(
    val memoSnapshotsEnabled: Boolean,
    val memoSnapshotMaxCount: Int,
    val memoSnapshotMaxAgeDays: Int,
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

data class WebDavSectionState(
    val enabled: Boolean,
    val provider: WebDavProvider,
    val baseUrl: String,
    val endpointUrl: String,
    val username: String,
    val passwordConfigured: Boolean,
    val autoSyncEnabled: Boolean,
    val autoSyncInterval: String,
    val syncOnRefreshEnabled: Boolean,
    val lastSyncTime: Long,
    val syncState: WebDavSyncState,
    val connectionTestState: SettingsWebDavConnectionTestState,
)

data class S3SectionState(
    val enabled: Boolean,
    val endpointUrl: String,
    val region: String,
    val bucket: String,
    val prefix: String,
    val localSyncDirectory: String,
    val accessKeyConfigured: Boolean,
    val secretAccessKeyConfigured: Boolean,
    val sessionTokenConfigured: Boolean,
    val pathStyle: S3PathStyle,
    val encryptionMode: S3EncryptionMode,
    val encryptionPasswordConfigured: Boolean,
    val encryptionPassword2Configured: Boolean,
    val rcloneFilenameEncryption: S3RcloneFilenameEncryption,
    val rcloneFilenameEncoding: S3RcloneFilenameEncoding,
    val rcloneDirectoryNameEncryption: Boolean,
    val rcloneDataEncryptionEnabled: Boolean,
    val rcloneEncryptedSuffix: String,
    val autoSyncEnabled: Boolean,
    val autoSyncInterval: String,
    val syncOnRefreshEnabled: Boolean,
    val lastSyncTime: Long,
    val syncState: S3SyncState,
    val connectionTestState: SettingsS3ConnectionTestState,
)

data class InteractionSectionState(
    val hapticEnabled: Boolean,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val appLockEnabled: Boolean,
    val quickSaveOnBackEnabled: Boolean,
)

data class SystemSectionState(
    val checkUpdatesOnStartup: Boolean,
)

data class AboutSectionState(
    val currentVersion: String,
    val manualUpdateState: SettingsManualUpdateState,
    val showDebugUpdateTools: Boolean,
)

data class SettingsScreenUiState(
    val storage: StorageSectionState,
    val display: DisplaySectionState,
    val lanShare: LanShareSectionState,
    val shareCard: ShareCardSectionState,
    val snapshot: SnapshotSectionState,
    val git: GitSectionState,
    val webDav: WebDavSectionState,
    val s3: S3SectionState,
    val interaction: InteractionSectionState,
    val system: SystemSectionState,
    val operationError: SettingsOperationError?,
)
