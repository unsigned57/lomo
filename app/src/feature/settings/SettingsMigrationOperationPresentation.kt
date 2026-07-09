package com.lomo.app.feature.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.lomo.app.R

@Composable
internal fun HandleMigrationOperationState(
    operationState: SettingsMigrationOperationState,
    snackbarHostState: SnackbarHostState,
    onClearOperationState: () -> Unit,
) {
    val message =
        when (operationState) {
            SettingsMigrationOperationState.Idle -> null
            is SettingsMigrationOperationState.Running -> null
            is SettingsMigrationOperationState.Success -> migrationSuccessMessage(operationState)
            is SettingsMigrationOperationState.Error -> operationState.message
        }
    LaunchedEffect(operationState) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onClearOperationState()
        }
    }
}

@Composable
private fun migrationSuccessMessage(state: SettingsMigrationOperationState.Success): String =
    when (state.kind) {
        SettingsMigrationOperationKind.EXPORT_NOTES ->
            stringResource(
                R.string.settings_migration_export_notes_success,
                state.summary.noteCount,
                state.summary.trashCount,
                state.summary.imageCount,
                state.summary.voiceCount,
            )
        SettingsMigrationOperationKind.IMPORT_NOTES ->
            stringResource(
                R.string.settings_migration_import_notes_success,
                state.summary.noteCount,
                state.summary.trashCount,
                state.summary.imageCount,
                state.summary.voiceCount,
            )
        SettingsMigrationOperationKind.EXPORT_SETTINGS ->
            stringResource(
                R.string.settings_migration_export_settings_success,
                state.settingsSummary.settingCount,
                state.settingsSummary.sensitiveSettingCount,
            )
        SettingsMigrationOperationKind.IMPORT_SETTINGS ->
            stringResource(
                R.string.settings_migration_import_settings_success,
                state.settingsSummary.settingCount,
                state.settingsSummary.sensitiveSettingCount,
            )
    }
