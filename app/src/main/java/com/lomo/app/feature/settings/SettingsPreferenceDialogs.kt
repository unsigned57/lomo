package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.ui.component.dialog.SelectionDialog
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

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
        options = options.dateFormats.toImmutableList(),
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
        options = options.timeFormats.toImmutableList(),
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
        options = options.themeModes.toImmutableList(),
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
        options = options.filenameFormats.toImmutableList(),
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
        options = options.timestampFormats.toImmutableList(),
        currentSelection = uiState.storage.timestampFormat,
        onDismiss = { dialogState.showTimestampDialog = false },
        onSelect = {
            storageFeature.updateStorageTimestampFormat(it)
            dialogState.showTimestampDialog = false
        },
    )
}

@Composable
internal fun ShareCardPreferenceDialogs(
    shareCardFeature: SettingsShareCardFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showShareCardSignatureDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showShareCardSignatureDialog = false },
        title = { Text(stringResource(R.string.settings_share_card_signature_dialog_title)) },
        text = {
            OutlinedTextField(
                value = dialogState.shareCardSignatureInput,
                onValueChange = { dialogState.shareCardSignatureInput = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_share_card_signature_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    shareCardFeature.updateShareCardSignatureText(dialogState.shareCardSignatureInput.trim())
                    dialogState.showShareCardSignatureDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showShareCardSignatureDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun <T> SelectionDialogIfVisible(
    visible: Boolean,
    title: String,
    options: ImmutableList<T>,
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

private val LANGUAGE_OPTIONS = listOf("system", "zh-CN", "en").toImmutableList()
