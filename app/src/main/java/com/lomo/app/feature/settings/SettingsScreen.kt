package com.lomo.app.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.conflict.SyncConflictDialogHost
import com.lomo.app.feature.conflict.SyncConflictViewModel
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
    val interaction: SettingsInteractionFeatureViewModel,
    val system: SettingsSystemFeatureViewModel,
    val lanShare: SettingsLanShareFeatureViewModel,
    val git: SettingsGitFeatureViewModel,
    val webDav: SettingsWebDavFeatureViewModel,
)

data class StoragePickerActions(
    val openRoot: () -> Unit,
    val openImage: () -> Unit,
    val openVoice: () -> Unit,
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
    val resources = settingsResources()
    val features = settingsFeatures(viewModel)
    val storagePickers =
        rememberStoragePickerActions(
            storageFeature = features.storage,
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
    SettingsScreenScaffold(
        uiState = uiState,
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
        dialogState = dialogState,
        features = features,
        resources = resources,
        storagePickers = storagePickers,
    )
    SyncConflictDialogHost(conflictViewModel = conflictViewModel)
    SettingsDialogHost(
        uiState = uiState,
        storageFeature = features.storage,
        displayFeature = features.display,
        lanShareFeature = features.lanShare,
        gitFeature = features.git,
        webDavFeature = features.webDav,
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
        interaction = viewModel.interactionFeature,
        system = viewModel.systemFeature,
        lanShare = viewModel.lanShareFeature,
        git = viewModel.gitFeature,
        webDav = viewModel.webDavFeature,
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
    return SettingsDialogOptions(
        dateFormats = DateFormatOptions,
        timeFormats = TimeFormatOptions,
        themeModes = ThemeMode.entries,
        filenameFormats = StorageFilenameFormats.supportedPatterns,
        timestampFormats = StorageTimestampFormats.supportedPatterns,
        gitSyncIntervals = GitSyncIntervalOptions,
        webDavProviders = WebDavProvider.entries,
        languageTag = currentLanguageTag,
        languageLabels = languageLabels,
        themeModeLabels = themeModeLabels,
        gitSyncIntervalLabels = gitSyncIntervalLabels,
        webDavProviderLabels = webDavProviderLabels,
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
