package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.WebDavProvider

data class SettingsDialogOptions(
    val dateFormats: List<String>,
    val timeFormats: List<String>,
    val themeModes: List<ThemeMode>,
    val filenameFormats: List<String>,
    val timestampFormats: List<String>,
    val gitSyncIntervals: List<String>,
    val snapshotRetentionCounts: List<Int>,
    val snapshotRetentionDays: List<Int>,
    val webDavProviders: List<WebDavProvider>,
    val s3PathStyles: List<S3PathStyle>,
    val s3EncryptionModes: List<S3EncryptionMode>,
    val languageTag: String,
    val languageLabels: Map<String, String>,
    val themeModeLabels: Map<ThemeMode, String>,
    val gitSyncIntervalLabels: Map<String, String>,
    val webDavProviderLabels: Map<WebDavProvider, String>,
    val s3PathStyleLabels: Map<S3PathStyle, String>,
    val s3EncryptionModeLabels: Map<S3EncryptionMode, String>,
)

@Composable
fun SettingsDialogHost(
    uiState: SettingsScreenUiState,
    storageFeature: SettingsStorageFeatureViewModel,
    displayFeature: SettingsDisplayFeatureViewModel,
    snapshotFeature: SettingsSnapshotFeatureViewModel,
    lanShareFeature: SettingsLanShareFeatureViewModel,
    gitFeature: SettingsGitFeatureViewModel,
    webDavFeature: SettingsWebDavFeatureViewModel,
    s3Feature: SettingsS3FeatureViewModel,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
    onApplyLanguageTag: (String) -> Unit,
) {
    DisplayPreferenceDialogs(
        uiState = uiState,
        displayFeature = displayFeature,
        dialogState = dialogState,
        options = options,
        onApplyLanguageTag = onApplyLanguageTag,
    )
    StoragePreferenceDialogs(
        uiState = uiState,
        storageFeature = storageFeature,
        dialogState = dialogState,
        options = options,
    )
    SnapshotPreferenceDialogs(
        uiState = uiState,
        snapshotFeature = snapshotFeature,
        dialogState = dialogState,
        options = options,
    )
    LanShareDialogs(
        uiState = uiState,
        lanShareFeature = lanShareFeature,
        dialogState = dialogState,
    )
    GitDialogs(
        uiState = uiState,
        gitFeature = gitFeature,
        dialogState = dialogState,
        options = options,
    )
    WebDavDialogs(
        uiState = uiState,
        webDavFeature = webDavFeature,
        dialogState = dialogState,
        options = options,
    )
    S3Dialogs(
        uiState = uiState,
        s3Feature = s3Feature,
        dialogState = dialogState,
        options = options,
    )
}
