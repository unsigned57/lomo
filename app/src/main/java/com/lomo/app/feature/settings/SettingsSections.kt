package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
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
fun StorageSettingsSection(
    state: StorageSectionState,
    onSelectRoot: () -> Unit,
    onSelectImageRoot: () -> Unit,
    onSelectVoiceRoot: () -> Unit,
    onToggleSyncInbox: (Boolean) -> Unit,
    onSelectSyncInbox: () -> Unit,
    onOpenFilenameFormatDialog: () -> Unit,
    onOpenTimestampFormatDialog: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_storage)) {
        val notSetLabel = stringResource(R.string.settings_not_set)
        PreferenceItem(
            title = stringResource(R.string.settings_memo_directory),
            subtitle = state.rootDirectory.subtitle(notSetLabel),
            icon = Icons.Default.Folder,
            onClick = onSelectRoot,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_image_storage),
            subtitle = state.imageDirectory.subtitle(notSetLabel),
            icon = Icons.Outlined.PhotoLibrary,
            onClick = onSelectImageRoot,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_voice_storage),
            subtitle = state.voiceDirectory.subtitle(notSetLabel),
            icon = Icons.Default.Audiotrack,
            onClick = onSelectVoiceRoot,
        )
        SettingsDivider()
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_sync_inbox_enabled),
            subtitle = stringResource(R.string.settings_sync_inbox_enabled_subtitle),
            icon = Icons.Outlined.Sync,
            checked = state.syncInboxEnabled,
            onCheckedChange = onToggleSyncInbox,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_sync_inbox_directory),
            subtitle = state.syncInboxDirectory.subtitle(notSetLabel),
            icon = Icons.Default.Folder,
            enabled = state.syncInboxEnabled,
            showChevron = state.syncInboxEnabled,
            onClick = onSelectSyncInbox,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_filename_format),
            subtitle = state.filenameFormat,
            icon = Icons.Outlined.Description,
            onClick = onOpenFilenameFormatDialog,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_timestamp_format),
            subtitle = state.timestampFormat,
            icon = Icons.Outlined.AccessTime,
            onClick = onOpenTimestampFormatDialog,
        )
    }
}

@Composable
fun DisplaySettingsSection(
    state: DisplaySectionState,
    languageLabel: String,
    themeLabel: String,
    onOpenLanguageDialog: () -> Unit,
    onOpenThemeDialog: () -> Unit,
    onOpenDateFormatDialog: () -> Unit,
    onOpenTimeFormatDialog: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_display)) {
        PreferenceItem(
            title = stringResource(R.string.settings_language),
            subtitle = languageLabel,
            icon = Icons.Outlined.Language,
            onClick = onOpenLanguageDialog,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_theme_mode),
            subtitle = themeLabel,
            icon = Icons.Outlined.Brightness6,
            onClick = onOpenThemeDialog,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_date_format),
            subtitle = state.dateFormat,
            icon = Icons.Outlined.CalendarToday,
            onClick = onOpenDateFormatDialog,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_time_format),
            subtitle = state.timeFormat,
            icon = Icons.Outlined.Schedule,
            onClick = onOpenTimeFormatDialog,
        )
    }
}

@Composable
fun ShareCardSettingsSection(
    state: ShareCardSectionState,
    onToggleShowTime: (Boolean) -> Unit,
    onToggleShowBrand: (Boolean) -> Unit,
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
    onToggleHaptic: (Boolean) -> Unit,
    onToggleInputHints: (Boolean) -> Unit,
    onToggleDoubleTapEdit: (Boolean) -> Unit,
    onToggleFreeTextCopy: (Boolean) -> Unit,
    onToggleMemoActionAutoReorder: (Boolean) -> Unit,
    onToggleAppLock: (Boolean) -> Unit,
    onToggleQuickSaveOnBack: (Boolean) -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_interaction)) {
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_haptic_feedback),
            subtitle = stringResource(R.string.settings_haptic_feedback_subtitle),
            icon = Icons.Default.Vibration,
            checked = state.hapticEnabled,
            onCheckedChange = onToggleHaptic,
        )
        SettingsDivider()
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_app_lock),
            subtitle = stringResource(R.string.settings_app_lock_subtitle),
            icon = Icons.Default.Lock,
            checked = state.appLockEnabled,
            onCheckedChange = onToggleAppLock,
        )
        SettingsDivider()
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
    }
}

@Composable
fun SystemSettingsSection(
    state: SystemSectionState,
    onToggleCheckUpdates: (Boolean) -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_system)) {
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_check_updates),
            subtitle = stringResource(R.string.settings_check_updates_subtitle),
            icon = Icons.Outlined.Schedule,
            checked = state.checkUpdatesOnStartup,
            onCheckedChange = onToggleCheckUpdates,
        )
    }
}

@Composable
fun AboutSettingsSection(
    state: AboutSectionState,
    onCheckUpdates: () -> Unit,
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
fun connectionTestSubtitle(state: SettingsGitConnectionTestState): String =
    when (state) {
        is SettingsGitConnectionTestState.Idle -> ""
        is SettingsGitConnectionTestState.Testing -> stringResource(R.string.settings_git_test_connection_testing)
        is SettingsGitConnectionTestState.Success -> stringResource(R.string.settings_git_test_connection_success)
        is SettingsGitConnectionTestState.Error ->
            stringResource(
                R.string.settings_git_test_connection_failed,
                SettingsErrorPresenter.gitSyncErrorMessage(state.code, state.detail),
            )
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

@Composable
fun connectionTestSubtitle(state: SettingsWebDavConnectionTestState): String =
    when (state) {
        is SettingsWebDavConnectionTestState.Idle -> ""
        is SettingsWebDavConnectionTestState.Testing ->
            stringResource(R.string.settings_webdav_test_connection_testing)
        is SettingsWebDavConnectionTestState.Success ->
            stringResource(R.string.settings_webdav_test_connection_success)
        is SettingsWebDavConnectionTestState.Error ->
            stringResource(
                R.string.settings_webdav_test_connection_failed,
                SettingsErrorPresenter.webDavSyncErrorMessage(state.code, state.detail),
            )
    }
