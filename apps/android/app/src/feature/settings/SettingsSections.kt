package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.ThemeMode
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SettingsGroup
import com.lomo.ui.component.settings.SwitchPreferenceItem
import com.lomo.ui.icon.LomoIcons
import kotlinx.collections.immutable.ImmutableMap

@Composable
fun FormatsSettingsSection(
    filenameFormat: String,
    timestampFormat: String,
    dateFormat: String,
    timeFormat: String,
    onOpenFilenameFormatDialog: () -> Unit,
    onOpenTimestampFormatDialog: () -> Unit,
    onOpenDateFormatDialog: () -> Unit,
    onOpenTimeFormatDialog: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_formats)) {
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_filename_format),
                subtitle = filenameFormat,
                icon = LomoIcons.Description,
                onClick = onOpenFilenameFormatDialog,
            )
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_timestamp_format),
                subtitle = timestampFormat,
                icon = LomoIcons.Schedule,
                onClick = onOpenTimestampFormatDialog,
            )
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_date_format),
                subtitle = dateFormat,
                icon = LomoIcons.CalendarToday,
                onClick = onOpenDateFormatDialog,
            )
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_time_format),
                subtitle = timeFormat,
                icon = LomoIcons.Schedule,
                onClick = onOpenTimeFormatDialog,
            )
        }
    }
}

@Composable
fun MigrationSettingsSection(
    operationState: SettingsMigrationOperationState,
    onExportNotesArchive: () -> Unit,
    onImportNotesArchive: () -> Unit,
    onOpenExportSettingsPasswordDialog: () -> Unit,
    onOpenImportSettingsPasswordDialog: () -> Unit,
) {
    val running = operationState is SettingsMigrationOperationState.Running
    SettingsGroup(title = stringResource(R.string.settings_group_migration)) {
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_migration_export_notes),
                subtitle = stringResource(R.string.settings_migration_export_notes_subtitle),
                icon = LomoIcons.Download,
                enabled = !running,
                onClick = onExportNotesArchive,
            )
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_migration_import_notes),
                subtitle = stringResource(R.string.settings_migration_import_notes_subtitle),
                icon = LomoIcons.Description,
                enabled = !running,
                onClick = onImportNotesArchive,
            )
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_migration_export_settings),
                subtitle = stringResource(R.string.settings_migration_export_settings_subtitle),
                icon = LomoIcons.Lock,
                enabled = !running,
                onClick = onOpenExportSettingsPasswordDialog,
            )
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_migration_import_settings),
                subtitle = stringResource(R.string.settings_migration_import_settings_subtitle),
                icon = LomoIcons.ContentCopy,
                enabled = !running,
                onClick = onOpenImportSettingsPasswordDialog,
            )
        }
    }
}

@Composable
fun ShareCardSettingsSection(
    state: ShareCardSectionState,
    onToggleShowTime: (Boolean) -> Unit,
    onToggleShowBrand: (Boolean) -> Unit,
    onOpenSignatureDialog: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_share_card)) {
        item {
            SwitchPreferenceItem(
                title = stringResource(R.string.settings_share_card_show_time),
                subtitle = stringResource(R.string.settings_share_card_show_time_subtitle),
                icon = LomoIcons.Schedule,
                checked = state.showTime,
                onCheckedChange = onToggleShowTime,
            )
        }
        item {
            SwitchPreferenceItem(
                title = stringResource(R.string.settings_share_card_show_brand),
                subtitle = stringResource(R.string.settings_share_card_show_brand_subtitle),
                icon = LomoIcons.Info,
                checked = state.showBrand,
                onCheckedChange = onToggleShowBrand,
            )
        }
        if (state.showBrand) {
            item {
                PreferenceItem(
                    title = stringResource(R.string.settings_share_card_signature_text),
                    subtitle = state.signatureText.ifBlank { stringResource(R.string.app_name) },
                    icon = LomoIcons.Tag,
                    onClick = onOpenSignatureDialog,
                )
            }
        }
    }
}

@Composable
fun SnapshotSettingsSection(
    state: SnapshotSectionState,
    onToggleMemoSnapshots: (Boolean) -> Unit,
    onOpenMemoCountDialog: () -> Unit,
    onOpenMemoAgeDialog: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_snapshots)) {
        item {
            SwitchPreferenceItem(
                title = stringResource(R.string.settings_memo_snapshots),
                subtitle = stringResource(R.string.settings_memo_snapshots_subtitle),
                icon = LomoIcons.History,
                checked = state.memoSnapshotsEnabled,
                onCheckedChange = onToggleMemoSnapshots,
            )
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_memo_snapshot_keep_count),
                subtitle = memoSnapshotKeepCountSummary(state.memoSnapshotMaxCount),
                icon = LomoIcons.ContentCopy,
                enabled = state.memoSnapshotsEnabled,
                showChevron = state.memoSnapshotsEnabled,
                onClick = onOpenMemoCountDialog,
            )
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_memo_snapshot_keep_age),
                subtitle = memoSnapshotKeepAgeSummary(state.memoSnapshotMaxAgeDays),
                icon = LomoIcons.Schedule,
                enabled = state.memoSnapshotsEnabled,
                showChevron = state.memoSnapshotsEnabled,
                onClick = onOpenMemoAgeDialog,
            )
        }
    }
}

@Composable
fun InteractionSettingsSection(
    state: InteractionSectionState,
    onToggleInputHints: (Boolean) -> Unit,
    onToggleDoubleTapEdit: (Boolean) -> Unit,
    onToggleFreeTextCopy: (Boolean) -> Unit,
    onToggleQuickSaveOnBack: (Boolean) -> Unit,
    onToggleScrollbar: (Boolean) -> Unit,
    onToggleAutoOpenInputOnForeground: (Boolean) -> Unit,
    onToggleAppLock: (Boolean) -> Unit,
    onToggleHaptic: (Boolean) -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_interaction)) {
        item {
            AppLockRow(checked = state.appLockEnabled, onCheckedChange = onToggleAppLock)
        }
        item {
            HapticRow(checked = state.hapticEnabled, onCheckedChange = onToggleHaptic)
        }
        item {
            SwitchPreferenceItem(
                title = stringResource(R.string.settings_show_input_hints),
                subtitle = stringResource(R.string.settings_show_input_hints_subtitle),
                icon = LomoIcons.Lightbulb,
                checked = state.showInputHints,
                onCheckedChange = onToggleInputHints,
            )
        }
        item {
            SwitchPreferenceItem(
                title = stringResource(R.string.settings_double_tap_edit),
                subtitle = stringResource(R.string.settings_double_tap_edit_subtitle),
                icon = LomoIcons.TouchApp,
                checked = state.doubleTapEditEnabled,
                onCheckedChange = onToggleDoubleTapEdit,
            )
        }
        item {
            SwitchPreferenceItem(
                title = stringResource(R.string.settings_free_text_copy),
                subtitle = stringResource(R.string.settings_free_text_copy_subtitle),
                icon = LomoIcons.ContentCopy,
                checked = state.freeTextCopyEnabled,
                onCheckedChange = onToggleFreeTextCopy,
            )
        }
        item {
            SwitchPreferenceItem(
                title = stringResource(R.string.settings_quick_save_on_back),
                subtitle = stringResource(R.string.settings_quick_save_on_back_subtitle),
                icon = LomoIcons.Save,
                checked = state.quickSaveOnBackEnabled,
                onCheckedChange = onToggleQuickSaveOnBack,
            )
        }
        item {
            SwitchPreferenceItem(
                title = stringResource(R.string.settings_auto_open_input_on_foreground),
                subtitle = stringResource(R.string.settings_auto_open_input_on_foreground_subtitle),
                icon = LomoIcons.Keyboard,
                checked = state.autoOpenInputOnForeground,
                onCheckedChange = onToggleAutoOpenInputOnForeground,
            )
        }
        item {
            SwitchPreferenceItem(
                title = stringResource(R.string.settings_scrollbar),
                subtitle = stringResource(R.string.settings_scrollbar_subtitle),
                icon = LomoIcons.SwapVert,
                checked = state.scrollbarEnabled,
                onCheckedChange = onToggleScrollbar,
            )
        }
    }
}

@Composable
fun AboutSettingsSection(
    state: AboutSectionState,
    systemState: SystemSectionState,
    filenameFormat: String,
    timestampFormat: String,
    onCheckUpdates: () -> Unit,
    onToggleCheckUpdatesOnStartup: (Boolean) -> Unit,
    onPreviewDebugUpdate: () -> Unit,
    onOpenGithub: () -> Unit,
) {
    var showEngineDiagnostics by remember { mutableStateOf(false) }
    SettingsGroup(title = stringResource(R.string.settings_group_about)) {
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_current_version),
                subtitle =
                state.currentVersion.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.settings_current_version_unknown),
                icon = LomoIcons.Info,
                showChevron = false,
            )
        }
        item {
            SwitchPreferenceItem(
                title = stringResource(R.string.settings_check_updates),
                subtitle = stringResource(R.string.settings_check_updates_subtitle),
                icon = LomoIcons.Schedule,
                checked = systemState.checkUpdatesOnStartup,
                onCheckedChange = onToggleCheckUpdatesOnStartup,
            )
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_check_updates_now),
                subtitle = manualUpdateSubtitle(state.manualUpdateState),
                subtitleMinLines = manualUpdateSubtitleMinLines(state.manualUpdateState),
                subtitleMaxLines = manualUpdateSubtitleMinLines(state.manualUpdateState),
                icon = LomoIcons.Download,
                enabled = state.manualUpdateState !is SettingsManualUpdateState.Checking,
                onClick = onCheckUpdates,
            )
        }
        if (state.showDebugUpdateTools) {
            item {
                PreferenceItem(
                    title = stringResource(R.string.settings_debug_update_preview),
                    subtitle = stringResource(R.string.settings_debug_update_preview_subtitle),
                    icon = LomoIcons.Download,
                    onClick = onPreviewDebugUpdate,
                )
            }
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_engine_diagnostics),
                subtitle = stringResource(R.string.settings_engine_diagnostics_subtitle),
                icon = LomoIcons.Info,
                onClick = { showEngineDiagnostics = true },
            )
        }
        item {
            PreferenceItem(
                title = stringResource(R.string.settings_github),
                subtitle = stringResource(R.string.settings_github_subtitle),
                icon = LomoIcons.Info,
                onClick = onOpenGithub,
            )
        }
    }
    if (showEngineDiagnostics) {
        EngineDiagnosticsDialog(
            appVersion = state.currentVersion,
            filenameFormat = filenameFormat,
            timestampFormat = timestampFormat,
            onDismiss = { showEngineDiagnostics = false },
        )
    }
}

fun themeModeLabel(mode: ThemeMode, labels: ImmutableMap<ThemeMode, String>): String = labels[mode] ?: mode.value
