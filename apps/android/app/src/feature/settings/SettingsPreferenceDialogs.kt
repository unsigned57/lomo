package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.component.dialog.SelectionDialog
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
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
    ThemePreferenceDialog(
        visible = dialogState.showThemeDialog,
        currentThemeMode = uiState.display.themeMode,
        pureBlackDark = uiState.display.pureBlackDark,
        options = options.themeModes.toImmutableList(),
        themeModeLabels = options.themeModeLabels,
        onSelectThemeMode = { displayFeature.updateThemeMode(it) },
        onTogglePureBlackDark = { displayFeature.updatePureBlackDark(it) },
        onDismiss = { dialogState.showThemeDialog = false },
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
    CalendarHeatmapThresholdPreferenceDialog(
        visible = dialogState.showCalendarHeatmapThresholdsDialog,
        currentThresholds = uiState.display.calendarHeatmapThresholds,
        onDismiss = { dialogState.showCalendarHeatmapThresholdsDialog = false },
        onSave = { thresholds ->
            displayFeature.updateCalendarHeatmapThresholds(thresholds)
            dialogState.showCalendarHeatmapThresholdsDialog = false
        },
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
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_share_card_signature_hint)) },
            )
        },
        confirmButton = {
            LomoTextButton(
                onClick = {
                    shareCardFeature.updateShareCardSignatureText(dialogState.shareCardSignatureInput.trim())
                    dialogState.showShareCardSignatureDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            LomoTextButton(onClick = { dialogState.showShareCardSignatureDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun MigrationPreferenceDialogs(dialogState: SettingsDialogState, migrationPickers: MigrationPickerActions) {
    MigrationPasswordDialog(
        visible = dialogState.showMigrationExportSettingsPasswordDialog,
        title = stringResource(R.string.settings_migration_export_settings_password_title),
        confirmLabel = stringResource(R.string.settings_migration_export_settings),
        password = dialogState.migrationPasswordInput,
        onPasswordChange = { dialogState.migrationPasswordInput = it },
        onDismiss = { dialogState.showMigrationExportSettingsPasswordDialog = false },
        onConfirm = {
            dialogState.showMigrationExportSettingsPasswordDialog = false
            migrationPickers.exportEncryptedSettings(dialogState.migrationPasswordInput)
        },
    )
    MigrationPasswordDialog(
        visible = dialogState.showMigrationImportSettingsPasswordDialog,
        title = stringResource(R.string.settings_migration_import_settings_password_title),
        confirmLabel = stringResource(R.string.settings_migration_import_settings),
        password = dialogState.migrationPasswordInput,
        onPasswordChange = { dialogState.migrationPasswordInput = it },
        onDismiss = { dialogState.showMigrationImportSettingsPasswordDialog = false },
        onConfirm = {
            dialogState.showMigrationImportSettingsPasswordDialog = false
            migrationPickers.importEncryptedSettings(dialogState.migrationPasswordInput)
        },
    )
}

@Composable
private fun MigrationPasswordDialog(
    visible: Boolean,
    title: String,
    confirmLabel: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) {
        return
    }
    MigrationPasswordDialogContent(
        title = title,
        confirmLabel = confirmLabel,
        password = password,
        onPasswordChange = onPasswordChange,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun MigrationPasswordDialogContent(
    title: String,
    confirmLabel: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val textState = remember { TextFieldState(password) }
    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }.collect { onPasswordChange(it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                state = textState,
                modifier = Modifier.fillMaxWidth(),
                lineLimits = TextFieldLineLimits.SingleLine,
                label = { Text(stringResource(R.string.settings_migration_password_hint)) },
                outputTransformation = PasswordOutputTransformation,
            )
        },
        confirmButton = {
            LomoTextButton(
                enabled = password.isNotBlank(),
                onClick = onConfirm,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            LomoTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun CalendarHeatmapThresholdPreferenceDialog(
    visible: Boolean,
    currentThresholds: CalendarHeatmapThresholds,
    onDismiss: () -> Unit,
    onSave: (CalendarHeatmapThresholds) -> Unit,
) {
    if (!visible) {
        return
    }

    var input by remember(currentThresholds, visible) {
        mutableStateOf(currentThresholds.toCalendarHeatmapThresholdEditorInput())
    }
    val editorState = remember(input) { resolveCalendarHeatmapThresholdEditorState(input) }
    val parsedThresholds = editorState.thresholds
    val rangeThresholds =
        if (parsedThresholds != null) {
            parsedThresholds
        } else {
            currentThresholds
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_calendar_heatmap_thresholds_dialog_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CalendarHeatmapThresholdRanges(thresholds = rangeThresholds)
                CalendarHeatmapBoundaryControl(
                    label = stringResource(R.string.settings_heatmap_threshold_level1),
                    value = input.level1Max,
                    onValueChange = { value -> input = input.copy(level1Max = value) },
                )
                CalendarHeatmapBoundaryControl(
                    label = stringResource(R.string.settings_heatmap_threshold_level2),
                    value = input.level2Max,
                    onValueChange = { value -> input = input.copy(level2Max = value) },
                )
                CalendarHeatmapBoundaryControl(
                    label = stringResource(R.string.settings_heatmap_threshold_level3),
                    value = input.level3Max,
                    onValueChange = { value -> input = input.copy(level3Max = value) },
                )
                editorState.validationError?.let { error ->
                    Text(
                        text =
                        when (error) {
                            CalendarHeatmapThresholdValidationError.NON_NUMERIC ->
                                stringResource(R.string.settings_heatmap_threshold_error_non_numeric)

                            CalendarHeatmapThresholdValidationError.OUT_OF_RANGE ->
                                stringResource(
                                    R.string.settings_heatmap_threshold_error_out_of_range,
                                    CalendarHeatmapThresholds.MIN_THRESHOLD,
                                    CalendarHeatmapThresholds.MAX_THRESHOLD,
                                )

                            CalendarHeatmapThresholdValidationError.NOT_STRICTLY_INCREASING ->
                                stringResource(R.string.settings_heatmap_threshold_error_increasing)
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            LomoTextButton(
                enabled = editorState.canSave,
                onClick = {
                    editorState.thresholds?.let(onSave)
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            LomoTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun CalendarHeatmapThresholdRanges(thresholds: CalendarHeatmapThresholds) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (label in calendarHeatmapThresholdRangeLabels(thresholds)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CalendarHeatmapBoundaryControl(label: String, value: String, onValueChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        LomoIconButton(
            onClick = { onValueChange(stepCalendarHeatmapThresholdValue(value, delta = -1)) },
        ) {
            LomoIcon(
                icon = LomoIcons.Remove,
                contentDescription = stringResource(R.string.cd_heatmap_threshold_decrement),
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.width(80.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        LomoIconButton(
            onClick = { onValueChange(stepCalendarHeatmapThresholdValue(value, delta = 1)) },
        ) {
            LomoIcon(
                icon = LomoIcons.Add,
                contentDescription = stringResource(R.string.cd_heatmap_threshold_increment),
            )
        }
    }
}

private fun stepCalendarHeatmapThresholdValue(value: String, delta: Int): String {
    val current = value.trim().toIntOrNull()
    if (current == null) {
        return value
    }
    return (current + delta)
        .coerceIn(CalendarHeatmapThresholds.MIN_THRESHOLD, CalendarHeatmapThresholds.MAX_THRESHOLD)
        .toString()
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
