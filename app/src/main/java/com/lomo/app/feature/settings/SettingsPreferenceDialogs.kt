package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.ui.component.dialog.SelectionDialog

@Composable
internal fun DisplayPreferenceDialogs(
    uiState: SettingsScreenUiState,
    displayFeature: SettingsDisplayFeatureViewModel,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
    onApplyLanguageTag: (String) -> Unit,
) {
    SelectionDialogIfVisible(
        visible = dialogState.showDateDialog,
        title = stringResource(R.string.settings_select_date_format),
        options = options.dateFormats,
        currentSelection = uiState.display.dateFormat,
        onDismiss = { dialogState.showDateDialog = false },
        onSelect = {
            displayFeature.updateDateFormat(it)
            dialogState.showDateDialog = false
        },
    )
    SelectionDialogIfVisible(
        visible = dialogState.showTimeDialog,
        title = stringResource(R.string.settings_select_time_format),
        options = options.timeFormats,
        currentSelection = uiState.display.timeFormat,
        onDismiss = { dialogState.showTimeDialog = false },
        onSelect = {
            displayFeature.updateTimeFormat(it)
            dialogState.showTimeDialog = false
        },
    )
    SelectionDialogIfVisible(
        visible = dialogState.showThemeDialog,
        title = stringResource(R.string.settings_select_theme),
        options = options.themeModes,
        currentSelection = uiState.display.themeMode,
        onDismiss = { dialogState.showThemeDialog = false },
        onSelect = {
            displayFeature.updateThemeMode(it)
            dialogState.showThemeDialog = false
        },
        labelProvider = { options.themeModeLabels[it] ?: it.value },
    )
    SelectionDialogIfVisible(
        visible = dialogState.showLanguageDialog,
        title = stringResource(R.string.settings_select_language),
        options = LANGUAGE_OPTIONS,
        currentSelection = options.languageTag,
        onDismiss = { dialogState.showLanguageDialog = false },
        onSelect = {
            onApplyLanguageTag(it)
            dialogState.showLanguageDialog = false
        },
        labelProvider = { options.languageLabels[it] ?: it },
    )
}

@Composable
internal fun StoragePreferenceDialogs(
    uiState: SettingsScreenUiState,
    storageFeature: SettingsStorageFeatureViewModel,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    SelectionDialogIfVisible(
        visible = dialogState.showFilenameDialog,
        title = stringResource(R.string.settings_select_filename_format),
        options = options.filenameFormats,
        currentSelection = uiState.storage.filenameFormat,
        onDismiss = { dialogState.showFilenameDialog = false },
        onSelect = {
            storageFeature.updateStorageFilenameFormat(it)
            dialogState.showFilenameDialog = false
        },
    )
    SelectionDialogIfVisible(
        visible = dialogState.showTimestampDialog,
        title = stringResource(R.string.settings_select_timestamp_format),
        options = options.timestampFormats,
        currentSelection = uiState.storage.timestampFormat,
        onDismiss = { dialogState.showTimestampDialog = false },
        onSelect = {
            storageFeature.updateStorageTimestampFormat(it)
            dialogState.showTimestampDialog = false
        },
    )
}

@Composable
internal fun <T> SelectionDialogIfVisible(
    visible: Boolean,
    title: String,
    options: List<T>,
    currentSelection: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    labelProvider: (T) -> String = { it.toString() },
) {
    if (!visible) {
        return
    }
    SelectionDialog(
        title = title,
        options = options,
        currentSelection = currentSelection,
        onDismiss = onDismiss,
        onSelect = onSelect,
        labelProvider = labelProvider,
    )
}

private val LANGUAGE_OPTIONS = listOf("system", "zh-CN", "en")
