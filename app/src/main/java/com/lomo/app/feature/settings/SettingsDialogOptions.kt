package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.WebDavProvider

data class SettingsDialogOptions(
    val dateFormats: List<String>,
    val timeFormats: List<String>,
    val themeModes: List<ThemeMode>,
    val filenameFormats: List<String>,
    val timestampFormats: List<String>,
    val gitSyncIntervals: List<String>,
    val webDavProviders: List<WebDavProvider>,
    val languageTag: String,
    val languageLabels: Map<String, String>,
    val themeModeLabels: Map<ThemeMode, String>,
    val gitSyncIntervalLabels: Map<String, String>,
    val webDavProviderLabels: Map<WebDavProvider, String>,
)

@Composable
fun SettingsDialogHost(
    uiState: SettingsScreenUiState,
    storageFeature: SettingsStorageFeatureViewModel,
    displayFeature: SettingsDisplayFeatureViewModel,
    lanShareFeature: SettingsLanShareFeatureViewModel,
    gitFeature: SettingsGitFeatureViewModel,
    webDavFeature: SettingsWebDavFeatureViewModel,
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
}
