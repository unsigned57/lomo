package com.lomo.app.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.conflict.SyncConflictDialogHost
import com.lomo.app.feature.conflict.SyncConflictViewModel
import com.lomo.app.feature.update.AppUpdateDialogState
import com.lomo.app.feature.update.LomoAppUpdateDialog
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.SnapshotPreferenceOptions
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.WebDavProvider

private const val SYSTEM_LANGUAGE_TAG = "system"
private const val ENGLISH_LANGUAGE_TAG = "en"
private const val SIMPLIFIED_CHINESE_LANGUAGE_TAG = "zh-CN"
private const val HANS_CHINESE_LANGUAGE_TAG = "zh-Hans-CN"
internal const val GITHUB_URL = "https://github.com/unsigned57/lomo"

private val DateFormatOptions = listOf("yyyy-MM-dd", "MM/dd/yyyy", "dd/MM/yyyy", "yyyy/MM/dd")
private val TimeFormatOptions = listOf("HH:mm", "hh:mm a", "HH:mm:ss", "hh:mm:ss a")
private val GitSyncIntervalOptions = listOf("30min", "1h", "6h", "12h", "24h")

data class SettingsMessages(
    val unknownErrorMessage: String,
    val gitConflictSummary: String,
    val gitDirectPathRequired: String,
)

data class SettingsResources(
    val currentLanguageTag: String,
    val dialogOptions: SettingsDialogOptions,
    val messages: SettingsMessages,
)

data class SettingsFeatures(
    val storage: SettingsStorageFeatureViewModel,
    val display: SettingsDisplayFeatureViewModel,
    val shareCard: SettingsShareCardFeatureViewModel,
    val snapshot: SettingsSnapshotFeatureViewModel,
    val interaction: SettingsInteractionFeatureViewModel,
    val system: SettingsSystemFeatureViewModel,
    val lanShare: SettingsLanShareFeatureViewModel,
    val git: SettingsGitFeatureViewModel,
    val webDav: SettingsWebDavFeatureViewModel,
    val s3: SettingsS3FeatureViewModel,
)

data class StoragePickerActions(
    val openRoot: () -> Unit,
    val openImage: () -> Unit,
    val openVoice: () -> Unit,
    val openS3LocalSyncDirectory: () -> Unit,
)

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    conflictViewModel: SyncConflictViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val dialogState = rememberSettingsDialogState()
    var activeUpdateDialogState by remember { mutableStateOf<AppUpdateDialogState?>(null) }
    var lastShownUpdateDialogKey by remember { mutableStateOf<String?>(null) }
    val resources = settingsResources()
    val features = settingsFeatures(viewModel)
    val currentVersion by features.system.currentVersion.collectAsStateWithLifecycle()
    val manualUpdateState by features.system.manualUpdateState.collectAsStateWithLifecycle()
    val storagePickers =
        rememberStoragePickerActions(
            storageFeature = features.storage,
            s3Feature = features.s3,
            snackbarHostState = snackbarHostState,
            unknownErrorMessage = resources.messages.unknownErrorMessage,
        )

    HandleSettingsOperationError(
        operationError = uiState.operationError,
        gitFeature = features.git,
        dialogState = dialogState,
        snackbarHostState = snackbarHostState,
        messages = resources.messages,
        onClearOperationError = viewModel::clearOperationError,
    )
    HandleGitConflictState(
        syncState = uiState.git.syncState,
        gitFeature = features.git,
        dialogState = dialogState,
        conflictViewModel = conflictViewModel,
    )
    HandleWebDavConflictState(
        syncState = uiState.webDav.syncState,
        conflictViewModel = conflictViewModel,
    )
    HandleS3ConflictState(
        syncState = uiState.s3.syncState,
        conflictViewModel = conflictViewModel,
    )
    LaunchedEffect(manualUpdateState) {
        val updateState = manualUpdateState as? SettingsManualUpdateState.UpdateAvailable ?: return@LaunchedEffect
        val dialogKey = "${updateState.dialogState.url}#${updateState.dialogState.version}"
        if (dialogKey != lastShownUpdateDialogKey) {
            lastShownUpdateDialogKey = dialogKey
            activeUpdateDialogState = updateState.dialogState
        }
    }
    SettingsScreenScaffold(
        uiState = uiState,
        aboutState =
            AboutSectionState(
                currentVersion = currentVersion,
                manualUpdateState = manualUpdateState,
            ),
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
        dialogState = dialogState,
        features = features,
        resources = resources,
        storagePickers = storagePickers,
        onOpenAvailableUpdateDialog = { activeUpdateDialogState = it },
    )
    LomoAppUpdateDialog(
        dialogState = activeUpdateDialogState,
        onDismiss = { activeUpdateDialogState = null },
    )
    SyncConflictDialogHost(conflictViewModel = conflictViewModel)
    SettingsDialogHost(
        uiState = uiState,
        storageFeature = features.storage,
        displayFeature = features.display,
        snapshotFeature = features.snapshot,
        lanShareFeature = features.lanShare,
        gitFeature = features.git,
        webDavFeature = features.webDav,
        s3Feature = features.s3,
        dialogState = dialogState,
        options = resources.dialogOptions,
        onApplyLanguageTag = ::applyLanguageTag,
    )
}

@Composable
private fun settingsResources(): SettingsResources {
    val currentLanguageTag = currentLanguageTag()
    return SettingsResources(
        currentLanguageTag = currentLanguageTag,
        dialogOptions = settingsDialogOptions(currentLanguageTag),
        messages = settingsMessages(),
    )
}

@Composable
private fun settingsMessages(): SettingsMessages =
    SettingsMessages(
        unknownErrorMessage = stringResource(R.string.error_unknown),
        gitConflictSummary = stringResource(R.string.settings_git_sync_conflict_summary),
        gitDirectPathRequired = stringResource(R.string.settings_git_sync_direct_path_required),
    )

private fun settingsFeatures(viewModel: SettingsViewModel): SettingsFeatures =
    SettingsFeatures(
        storage = viewModel.storageFeature,
        display = viewModel.displayFeature,
        shareCard = viewModel.shareCardFeature,
        snapshot = viewModel.snapshotFeature,
        interaction = viewModel.interactionFeature,
        system = viewModel.systemFeature,
        lanShare = viewModel.lanShareFeature,
        git = viewModel.gitFeature,
        webDav = viewModel.webDavFeature,
        s3 = viewModel.s3Feature,
    )

private fun currentLanguageTag(): String {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (!locales.isEmpty) {
        return locales[0]?.toLanguageTag() ?: SYSTEM_LANGUAGE_TAG
    }
    return SYSTEM_LANGUAGE_TAG
}

@Composable
private fun settingsDialogOptions(currentLanguageTag: String): SettingsDialogOptions {
    val themeModeLabels =
        mapOf(
            ThemeMode.SYSTEM to stringResource(R.string.settings_system),
            ThemeMode.LIGHT to stringResource(R.string.settings_light_mode),
            ThemeMode.DARK to stringResource(R.string.settings_dark_mode),
        )
    val languageLabels =
        mapOf(
            SYSTEM_LANGUAGE_TAG to stringResource(R.string.settings_system),
            ENGLISH_LANGUAGE_TAG to stringResource(R.string.settings_english),
            SIMPLIFIED_CHINESE_LANGUAGE_TAG to stringResource(R.string.settings_simplified_chinese),
            HANS_CHINESE_LANGUAGE_TAG to stringResource(R.string.settings_simplified_chinese),
        )
    val gitSyncIntervalLabels =
        mapOf(
            "30min" to stringResource(R.string.settings_git_sync_interval_30min),
            "1h" to stringResource(R.string.settings_git_sync_interval_1h),
            "6h" to stringResource(R.string.settings_git_sync_interval_6h),
            "12h" to stringResource(R.string.settings_git_sync_interval_12h),
            "24h" to stringResource(R.string.settings_git_sync_interval_24h),
        )
    val webDavProviderLabels =
        mapOf(
            WebDavProvider.NUTSTORE to stringResource(R.string.settings_webdav_provider_nutstore),
            WebDavProvider.NEXTCLOUD to stringResource(R.string.settings_webdav_provider_nextcloud),
            WebDavProvider.CUSTOM to stringResource(R.string.settings_webdav_provider_custom),
        )
    val s3PathStyleLabels =
        mapOf(
            S3PathStyle.AUTO to stringResource(R.string.settings_s3_path_style_auto),
            S3PathStyle.PATH_STYLE to stringResource(R.string.settings_s3_path_style_path_style),
            S3PathStyle.VIRTUAL_HOSTED to stringResource(R.string.settings_s3_path_style_virtual_hosted),
        )
    val s3EncryptionModeLabels =
        mapOf(
            S3EncryptionMode.NONE to stringResource(R.string.settings_s3_encryption_mode_none),
            S3EncryptionMode.RCLONE_CRYPT to stringResource(R.string.settings_s3_encryption_mode_rclone_crypt),
        )
    val s3RcloneFilenameEncryptionLabels =
        mapOf(
            S3RcloneFilenameEncryption.STANDARD to
                stringResource(R.string.settings_s3_rclone_filename_encryption_standard),
            S3RcloneFilenameEncryption.OBFUSCATE to
                stringResource(R.string.settings_s3_rclone_filename_encryption_obfuscate),
            S3RcloneFilenameEncryption.OFF to
                stringResource(R.string.settings_s3_rclone_filename_encryption_off),
        )
    val s3RcloneFilenameEncodingLabels =
        mapOf(
            S3RcloneFilenameEncoding.BASE64 to stringResource(R.string.settings_s3_rclone_filename_encoding_base64),
            S3RcloneFilenameEncoding.BASE32 to stringResource(R.string.settings_s3_rclone_filename_encoding_base32),
            S3RcloneFilenameEncoding.BASE32768 to
                stringResource(R.string.settings_s3_rclone_filename_encoding_base32768),
        )
    return SettingsDialogOptions(
        dateFormats = DateFormatOptions,
        timeFormats = TimeFormatOptions,
        themeModes = ThemeMode.entries,
        filenameFormats = StorageFilenameFormats.supportedPatterns,
        timestampFormats = StorageTimestampFormats.supportedPatterns,
        gitSyncIntervals = GitSyncIntervalOptions,
        snapshotRetentionCounts = SnapshotPreferenceOptions.RETENTION_COUNT_OPTIONS,
        snapshotRetentionDays = SnapshotPreferenceOptions.RETENTION_DAY_OPTIONS,
        webDavProviders = WebDavProvider.entries,
        s3PathStyles = S3PathStyle.entries,
        s3EncryptionModes = S3EncryptionMode.entries,
        s3RcloneFilenameEncryptions = S3RcloneFilenameEncryption.entries,
        s3RcloneFilenameEncodings = S3RcloneFilenameEncoding.entries,
        languageTag = currentLanguageTag,
        languageLabels = languageLabels,
        themeModeLabels = themeModeLabels,
        gitSyncIntervalLabels = gitSyncIntervalLabels,
        webDavProviderLabels = webDavProviderLabels,
        s3PathStyleLabels = s3PathStyleLabels,
        s3EncryptionModeLabels = s3EncryptionModeLabels,
        s3RcloneFilenameEncryptionLabels = s3RcloneFilenameEncryptionLabels,
        s3RcloneFilenameEncodingLabels = s3RcloneFilenameEncodingLabels,
    )
}

private fun applyLanguageTag(tag: String) {
    val locales =
        if (tag == SYSTEM_LANGUAGE_TAG) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
    AppCompatDelegate.setApplicationLocales(locales)
}
