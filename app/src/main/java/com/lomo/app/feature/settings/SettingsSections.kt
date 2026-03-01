package com.lomo.app.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
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
import com.lomo.ui.theme.MotionTokens

@Composable
fun StorageSettingsSection(
    state: StorageSectionState,
    onSelectRoot: () -> Unit,
    onSelectImageRoot: () -> Unit,
    onSelectVoiceRoot: () -> Unit,
    onOpenFilenameFormatDialog: () -> Unit,
    onOpenTimestampFormatDialog: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_storage)) {
        PreferenceItem(
            title = stringResource(R.string.settings_memo_directory),
            subtitle = state.rootDirectory.ifBlank { stringResource(R.string.settings_not_set) },
            icon = Icons.Default.Folder,
            onClick = onSelectRoot,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_image_storage),
            subtitle = state.imageDirectory.ifBlank { stringResource(R.string.settings_not_set) },
            icon = Icons.Outlined.PhotoLibrary,
            onClick = onSelectImageRoot,
        )
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_voice_storage),
            subtitle = state.voiceDirectory.ifBlank { stringResource(R.string.settings_not_set) },
            icon = Icons.Default.Audiotrack,
            onClick = onSelectVoiceRoot,
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
fun LanShareSettingsSection(
    state: LanShareSectionState,
    onToggleE2e: (Boolean) -> Unit,
    onOpenPairingDialog: () -> Unit,
    onOpenDeviceNameDialog: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.share_lan_title)) {
        SwitchPreferenceItem(
            title = stringResource(R.string.share_e2e_enabled_title),
            subtitle = stringResource(R.string.share_e2e_enabled_subtitle),
            icon = Icons.Default.Lock,
            checked = state.e2eEnabled,
            onCheckedChange = onToggleE2e,
        )
        AnimatedVisibility(
            visible = state.e2eEnabled,
            enter =
                expandVertically(
                    animationSpec =
                        tween(
                            durationMillis = MotionTokens.DurationMedium2,
                            easing = MotionTokens.EasingEmphasizedDecelerate,
                        ),
                ) +
                    fadeIn(
                        animationSpec =
                            tween(
                                durationMillis = MotionTokens.DurationMedium2,
                                easing = MotionTokens.EasingEmphasizedDecelerate,
                            ),
                    ),
            exit =
                shrinkVertically(
                    animationSpec =
                        tween(
                            durationMillis = MotionTokens.DurationShort4,
                            easing = MotionTokens.EasingEmphasizedAccelerate,
                        ),
                ) +
                    fadeOut(
                        animationSpec =
                            tween(
                                durationMillis = MotionTokens.DurationShort4,
                                easing = MotionTokens.EasingEmphasizedAccelerate,
                            ),
                    ),
            label = "LanPairingVisibility",
        ) {
            Column {
                SettingsDivider()
                PreferenceItem(
                    title = stringResource(R.string.settings_lan_share_pairing_code),
                    subtitle =
                        stringResource(
                            if (state.pairingConfigured) {
                                R.string.settings_lan_share_pairing_configured
                            } else {
                                R.string.settings_lan_share_pairing_not_set
                            },
                        ),
                    icon = Icons.Default.Lock,
                    onClick = onOpenPairingDialog,
                )
            }
        }
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.share_device_name_label),
            subtitle = state.deviceName.ifBlank { stringResource(R.string.settings_not_set) },
            icon = Icons.Outlined.PhoneAndroid,
            onClick = onOpenDeviceNameDialog,
        )
    }
}

@Composable
fun ShareCardSettingsSection(
    state: ShareCardSectionState,
    styleLabel: String,
    onOpenStyleDialog: () -> Unit,
    onToggleShowTime: (Boolean) -> Unit,
    onToggleShowBrand: (Boolean) -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_share_card)) {
        PreferenceItem(
            title = stringResource(R.string.settings_share_card_style),
            subtitle = styleLabel,
            icon = Icons.Outlined.Description,
            onClick = onOpenStyleDialog,
        )
        SettingsDivider()
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
fun GitSyncSettingsSection(
    state: GitSectionState,
    syncIntervalLabel: String,
    syncNowSubtitle: String,
    connectionSubtitle: String,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenRemoteUrlDialog: () -> Unit,
    onOpenPatDialog: () -> Unit,
    onOpenAuthorNameDialog: () -> Unit,
    onOpenAuthorEmailDialog: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onOpenSyncIntervalDialog: () -> Unit,
    onToggleSyncOnRefresh: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onTestConnection: () -> Unit,
    onOpenResetDialog: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_git_sync)) {
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_git_sync_enable),
            subtitle = stringResource(R.string.settings_git_sync_enable_subtitle),
            icon = Icons.Outlined.Sync,
            checked = state.enabled,
            onCheckedChange = onToggleEnabled,
        )
        AnimatedVisibility(
            visible = state.enabled,
            enter =
                expandVertically(
                    animationSpec =
                        tween(
                            durationMillis = MotionTokens.DurationMedium2,
                            easing = MotionTokens.EasingEmphasizedDecelerate,
                        ),
                ) +
                    fadeIn(
                        animationSpec =
                            tween(
                                durationMillis = MotionTokens.DurationMedium2,
                                easing = MotionTokens.EasingEmphasizedDecelerate,
                            ),
                    ),
            exit =
                shrinkVertically(
                    animationSpec =
                        tween(
                            durationMillis = MotionTokens.DurationShort4,
                            easing = MotionTokens.EasingEmphasizedAccelerate,
                        ),
                ) +
                    fadeOut(
                        animationSpec =
                            tween(
                                durationMillis = MotionTokens.DurationShort4,
                                easing = MotionTokens.EasingEmphasizedAccelerate,
                            ),
                    ),
            label = "GitSyncAdvancedVisibility",
        ) {
            Column {
                SettingsDivider()
                PreferenceItem(
                    title = stringResource(R.string.settings_git_remote_url),
                    subtitle = state.remoteUrl.ifBlank { stringResource(R.string.settings_not_set) },
                    icon = Icons.Outlined.Link,
                    onClick = onOpenRemoteUrlDialog,
                )
                SettingsDivider()
                PreferenceItem(
                    title = stringResource(R.string.settings_git_pat),
                    subtitle =
                        stringResource(
                            if (state.patConfigured) {
                                R.string.settings_git_pat_configured
                            } else {
                                R.string.settings_git_pat_not_set
                            },
                        ),
                    icon = Icons.Default.Lock,
                    onClick = onOpenPatDialog,
                )
                SettingsDivider()
                PreferenceItem(
                    title = stringResource(R.string.settings_git_author_name),
                    subtitle = state.authorName.ifBlank { stringResource(R.string.settings_not_set) },
                    icon = Icons.Outlined.Person,
                    onClick = onOpenAuthorNameDialog,
                )
                SettingsDivider()
                PreferenceItem(
                    title = stringResource(R.string.settings_git_author_email),
                    subtitle = state.authorEmail.ifBlank { stringResource(R.string.settings_not_set) },
                    icon = Icons.Outlined.Email,
                    onClick = onOpenAuthorEmailDialog,
                )
                SettingsDivider()
                SwitchPreferenceItem(
                    title = stringResource(R.string.settings_git_auto_sync),
                    subtitle = stringResource(R.string.settings_git_auto_sync_subtitle),
                    icon = Icons.Outlined.Schedule,
                    checked = state.autoSyncEnabled,
                    onCheckedChange = onToggleAutoSync,
                )
                AnimatedVisibility(
                    visible = state.autoSyncEnabled,
                    enter =
                        expandVertically(
                            animationSpec =
                                tween(
                                    durationMillis = MotionTokens.DurationMedium2,
                                    easing = MotionTokens.EasingEmphasizedDecelerate,
                                ),
                        ) +
                            fadeIn(
                                animationSpec =
                                    tween(
                                        durationMillis = MotionTokens.DurationMedium2,
                                        easing = MotionTokens.EasingEmphasizedDecelerate,
                                    ),
                            ),
                    exit =
                        shrinkVertically(
                            animationSpec =
                                tween(
                                    durationMillis = MotionTokens.DurationShort4,
                                    easing = MotionTokens.EasingEmphasizedAccelerate,
                                ),
                        ) +
                            fadeOut(
                                animationSpec =
                                    tween(
                                        durationMillis = MotionTokens.DurationShort4,
                                        easing = MotionTokens.EasingEmphasizedAccelerate,
                                    ),
                            ),
                    label = "GitAutoSyncIntervalVisibility",
                ) {
                    Column {
                        SettingsDivider()
                        PreferenceItem(
                            title = stringResource(R.string.settings_git_sync_interval),
                            subtitle = syncIntervalLabel,
                            icon = Icons.Outlined.Schedule,
                            onClick = onOpenSyncIntervalDialog,
                        )
                    }
                }
                SettingsDivider()
                SwitchPreferenceItem(
                    title = stringResource(R.string.settings_git_sync_on_refresh),
                    subtitle = stringResource(R.string.settings_git_sync_on_refresh_subtitle),
                    icon = Icons.Outlined.Refresh,
                    checked = state.syncOnRefreshEnabled,
                    onCheckedChange = onToggleSyncOnRefresh,
                )
                SettingsDivider()
                PreferenceItem(
                    title = stringResource(R.string.settings_git_sync_now),
                    subtitle = syncNowSubtitle,
                    icon = Icons.Outlined.Sync,
                    onClick = onSyncNow,
                )
                SettingsDivider()
                PreferenceItem(
                    title = stringResource(R.string.settings_git_test_connection),
                    subtitle = connectionSubtitle,
                    icon = Icons.Outlined.Link,
                    onClick = onTestConnection,
                )
                SettingsDivider()
                PreferenceItem(
                    title = stringResource(R.string.settings_git_reset_repo),
                    subtitle = stringResource(R.string.settings_git_reset_repo_subtitle),
                    icon = Icons.Outlined.DeleteForever,
                    onClick = onOpenResetDialog,
                )
            }
        }
    }
}

@Composable
fun InteractionSettingsSection(
    state: InteractionSectionState,
    onToggleHaptic: (Boolean) -> Unit,
    onToggleInputHints: (Boolean) -> Unit,
    onToggleDoubleTapEdit: (Boolean) -> Unit,
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
fun AboutSettingsSection(onOpenGithub: () -> Unit) {
    SettingsGroup(title = stringResource(R.string.settings_group_about)) {
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
        is SettingsGitConnectionTestState.Testing ->
            stringResource(R.string.settings_git_test_connection_testing)
        is SettingsGitConnectionTestState.Success ->
            stringResource(R.string.settings_git_test_connection_success)
        is SettingsGitConnectionTestState.Error ->
            stringResource(
                R.string.settings_git_test_connection_failed,
                state.message,
            )
    }

@Composable
fun themeModeLabel(
    mode: ThemeMode,
    labels: Map<ThemeMode, String>,
): String = labels[mode] ?: mode.value

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = AppSpacing.ScreenHorizontalPadding),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}
