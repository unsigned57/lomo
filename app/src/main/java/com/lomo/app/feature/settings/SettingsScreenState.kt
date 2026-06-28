package com.lomo.app.feature.settings

import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.domain.model.CustomFontInfo
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.WebDavProvider
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

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
    val calendarHeatmapThresholds: CalendarHeatmapThresholds,
    val colorSource: ColorSource,
    val fontPreference: FontPreference,
    val availableCustomFonts: ImmutableList<CustomFontInfo> = persistentListOf(),
    val colorHistory: ImmutableList<Int> = persistentListOf(),
    val typographyFontSizeScale: Float,
    val typographyLineHeightScale: Float,
    val typographyLetterSpacingScale: Float,
    val typographyParagraphSpacingScale: Float,
)

data class LanShareSectionState(
    val enabled: Boolean,
    val e2eEnabled: Boolean,
    val pairingConfigured: Boolean,
    val deviceName: String,
    val pairingCodeError: String?,
)

data class ShareCardSectionState(
    val showTime: Boolean,
    val showBrand: Boolean,
    val signatureText: String = PreferenceDefaults.SHARE_CARD_SIGNATURE_TEXT,
)

data class SnapshotSectionState(
    val memoSnapshotsEnabled: Boolean,
    val memoSnapshotMaxCount: Int,
    val memoSnapshotMaxAgeDays: Int,
)

data class GitSectionState(
    val providerSettings: RemoteProviderSettingsModel,
    val remoteUrl: String,
    val authorName: String,
    val authorEmail: String,
    val resetInProgress: Boolean,
)

data class WebDavSectionState(
    val providerSettings: RemoteProviderSettingsModel,
    val provider: WebDavProvider,
    val baseUrl: String,
    val endpointUrl: String,
    val username: String,
)

data class S3SectionState(
    val providerSettings: RemoteProviderSettingsModel,
    val endpointUrl: String,
    val region: String,
    val bucket: String,
    val prefix: String,
    val localSyncDirectory: String,
    val pathStyle: S3PathStyle,
    val encryptionMode: S3EncryptionMode,
    val rcloneFilenameEncryption: S3RcloneFilenameEncryption,
    val rcloneFilenameEncoding: S3RcloneFilenameEncoding,
    val rcloneDirectoryNameEncryption: Boolean,
    val rcloneDataEncryptionEnabled: Boolean,
    val rcloneEncryptedSuffix: String,
)

data class InteractionSectionState(
    val hapticEnabled: Boolean,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val appLockEnabled: Boolean,
    val quickSaveOnBackEnabled: Boolean,
    val scrollbarEnabled: Boolean,
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
