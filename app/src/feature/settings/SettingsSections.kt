package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.ThemeMode
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SettingsGroup
import com.lomo.ui.component.settings.SwitchPreferenceItem
import com.lomo.ui.theme.AppSpacing
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
        PreferenceItem(
            title = stringResource(R.string.settings_filename_format),
            subtitle = filenameFormat,
            icon = Icons.Outlined.Description,
            onClick = onOpenFilenameFormatDialog,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_timestamp_format),
            subtitle = timestampFormat,
            icon = Icons.Outlined.AccessTime,
            onClick = onOpenTimestampFormatDialog,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_date_format),
            subtitle = dateFormat,
            icon = Icons.Outlined.CalendarToday,
            onClick = onOpenDateFormatDialog,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_time_format),
            subtitle = timeFormat,
            icon = Icons.Outlined.Schedule,
            onClick = onOpenTimeFormatDialog,
        )
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
        PreferenceItem(
            title = stringResource(R.string.settings_migration_export_notes),
            subtitle = stringResource(R.string.settings_migration_export_notes_subtitle),
            icon = Icons.Outlined.Download,
            enabled = !running,
            onClick = onExportNotesArchive,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_migration_import_notes),
            subtitle = stringResource(R.string.settings_migration_import_notes_subtitle),
            icon = Icons.Outlined.Description,
            enabled = !running,
            onClick = onImportNotesArchive,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_migration_export_settings),
            subtitle = stringResource(R.string.settings_migration_export_settings_subtitle),
            icon = Icons.Outlined.Lock,
            enabled = !running,
            onClick = onOpenExportSettingsPasswordDialog,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_migration_import_settings),
            subtitle = stringResource(R.string.settings_migration_import_settings_subtitle),
            icon = Icons.Outlined.ContentCopy,
            enabled = !running,
            onClick = onOpenImportSettingsPasswordDialog,
        )
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
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_share_card_show_time),
            subtitle = stringResource(R.string.settings_share_card_show_time_subtitle),
            icon = Icons.Outlined.Schedule,
            checked = state.showTime,
            onCheckedChange = onToggleShowTime,
        )
        SettingsDivider()
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_share_card_show_brand),
            subtitle = stringResource(R.string.settings_share_card_show_brand_subtitle),
            icon = Icons.Outlined.Info,
            checked = state.showBrand,
            onCheckedChange = onToggleShowBrand,
        )
        if (state.showBrand) {
            SettingsDivider()
            PreferenceItem(
                title = stringResource(R.string.settings_share_card_signature_text),
                subtitle = state.signatureText.ifBlank { stringResource(R.string.app_name) },
                icon = Icons.Outlined.Tag,
                onClick = onOpenSignatureDialog,
            )
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
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_memo_snapshots),
            subtitle = stringResource(R.string.settings_memo_snapshots_subtitle),
            icon = Icons.Outlined.History,
            checked = state.memoSnapshotsEnabled,
            onCheckedChange = onToggleMemoSnapshots,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_memo_snapshot_keep_count),
            subtitle = memoSnapshotKeepCountSummary(state.memoSnapshotMaxCount),
            icon = Icons.Outlined.ContentCopy,
            enabled = state.memoSnapshotsEnabled,
            showChevron = state.memoSnapshotsEnabled,
            onClick = onOpenMemoCountDialog,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_memo_snapshot_keep_age),
            subtitle = memoSnapshotKeepAgeSummary(state.memoSnapshotMaxAgeDays),
            icon = Icons.Outlined.AccessTime,
            enabled = state.memoSnapshotsEnabled,
            showChevron = state.memoSnapshotsEnabled,
            onClick = onOpenMemoAgeDialog,
        )
    }
}

@Composable
fun InteractionSettingsSection(
    state: InteractionSectionState,
    onToggleInputHints: (Boolean) -> Unit,
    onToggleDoubleTapEdit: (Boolean) -> Unit,
    onToggleFreeTextCopy: (Boolean) -> Unit,
    onToggleMemoActionAutoReorder: (Boolean) -> Unit,
    onToggleQuickSaveOnBack: (Boolean) -> Unit,
    onToggleScrollbar: (Boolean) -> Unit,
    onToggleAutoOpenInputOnForeground: (Boolean) -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_interaction)) {
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_show_input_hints),
            subtitle = stringResource(R.string.settings_show_input_hints_subtitle),
            icon = Icons.Outlined.Info,
            checked = state.showInputHints,
            onCheckedChange = onToggleInputHints,
        )
        SettingsDivider()
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_double_tap_edit),
            subtitle = stringResource(R.string.settings_double_tap_edit_subtitle),
            icon = Icons.Outlined.Info,
            checked = state.doubleTapEditEnabled,
            onCheckedChange = onToggleDoubleTapEdit,
        )
        SettingsDivider()
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_free_text_copy),
            subtitle = stringResource(R.string.settings_free_text_copy_subtitle),
            icon = Icons.Outlined.ContentCopy,
            checked = state.freeTextCopyEnabled,
            onCheckedChange = onToggleFreeTextCopy,
        )
        SettingsDivider()
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_menu_auto_reorder),
            subtitle = stringResource(R.string.settings_menu_auto_reorder_subtitle),
            icon = Icons.Outlined.Schedule,
            checked = state.memoActionAutoReorderEnabled,
            onCheckedChange = onToggleMemoActionAutoReorder,
        )
        SettingsDivider()
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_quick_save_on_back),
            subtitle = stringResource(R.string.settings_quick_save_on_back_subtitle),
            icon = Icons.Outlined.Info,
            checked = state.quickSaveOnBackEnabled,
            onCheckedChange = onToggleQuickSaveOnBack,
        )
        SettingsDivider()
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_auto_open_input_on_foreground),
            subtitle = stringResource(R.string.settings_auto_open_input_on_foreground_subtitle),
            icon = Icons.Outlined.Keyboard,
            checked = state.autoOpenInputOnForeground,
            onCheckedChange = onToggleAutoOpenInputOnForeground,
        )
        SettingsDivider()
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_scrollbar),
            subtitle = stringResource(R.string.settings_scrollbar_subtitle),
            icon = Icons.Outlined.Info,
            checked = state.scrollbarEnabled,
            onCheckedChange = onToggleScrollbar,
        )
    }
}

@Composable
fun AboutSettingsSection(
    state: AboutSectionState,
    systemState: SystemSectionState,
    onCheckUpdates: () -> Unit,
    onToggleCheckUpdatesOnStartup: (Boolean) -> Unit,
    onPreviewDebugUpdate: () -> Unit,
    onOpenGithub: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_about)) {
        PreferenceItem(
            title = stringResource(R.string.settings_current_version),
            subtitle =
                state.currentVersion.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.settings_current_version_unknown),
            icon = Icons.Outlined.Info,
            showChevron = false,
        )
        SettingsDivider()
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_check_updates),
            subtitle = stringResource(R.string.settings_check_updates_subtitle),
            icon = Icons.Outlined.Schedule,
            checked = systemState.checkUpdatesOnStartup,
            onCheckedChange = onToggleCheckUpdatesOnStartup,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_check_updates_now),
            subtitle = manualUpdateSubtitle(state.manualUpdateState),
            subtitleMinLines = manualUpdateSubtitleMinLines(state.manualUpdateState),
            icon = Icons.Outlined.Download,
            enabled = state.manualUpdateState !is SettingsManualUpdateState.Checking,
            onClick = onCheckUpdates,
        )
        SettingsDivider()
        if (state.showDebugUpdateTools) {
            PreferenceItem(
                title = stringResource(R.string.settings_debug_update_preview),
                subtitle = stringResource(R.string.settings_debug_update_preview_subtitle),
                icon = Icons.Outlined.Download,
                onClick = onPreviewDebugUpdate,
            )
            SettingsDivider()
        }
        PreferenceItem(
            title = stringResource(R.string.settings_github),
            subtitle = stringResource(R.string.settings_github_subtitle),
            icon = Icons.Outlined.Info,
            onClick = onOpenGithub,
        )
    }
}

@Composable
fun themeModeLabel(
    mode: ThemeMode,
    labels: ImmutableMap<ThemeMode, String>,
): String = labels[mode] ?: mode.value

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = AppSpacing.ScreenHorizontalPadding),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}
