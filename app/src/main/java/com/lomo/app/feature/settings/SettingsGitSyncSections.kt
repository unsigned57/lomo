package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SwitchPreferenceItem

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
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_git_sync_enable),
        subtitle = stringResource(R.string.settings_git_sync_enable_subtitle),
        icon = Icons.Outlined.Sync,
        checked = state.enabled,
        onCheckedChange = onToggleEnabled,
    )
    SettingsExpandableContent(
        visible = state.enabled,
        label = "GitSyncAdvancedVisibility",
    ) {
        GitSyncAdvancedContent(
            state = state,
            syncIntervalLabel = syncIntervalLabel,
            syncNowSubtitle = syncNowSubtitle,
            connectionSubtitle = connectionSubtitle,
            onOpenRemoteUrlDialog = onOpenRemoteUrlDialog,
            onOpenPatDialog = onOpenPatDialog,
            onOpenAuthorNameDialog = onOpenAuthorNameDialog,
            onOpenAuthorEmailDialog = onOpenAuthorEmailDialog,
            onToggleAutoSync = onToggleAutoSync,
            onOpenSyncIntervalDialog = onOpenSyncIntervalDialog,
            onToggleSyncOnRefresh = onToggleSyncOnRefresh,
            onSyncNow = onSyncNow,
            onTestConnection = onTestConnection,
            onOpenResetDialog = onOpenResetDialog,
        )
    }
}

@Composable
private fun GitSyncAdvancedContent(
    state: GitSectionState,
    syncIntervalLabel: String,
    syncNowSubtitle: String,
    connectionSubtitle: String,
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
    Column {
        GitSyncConnectionPreferences(
            state = state,
            onOpenRemoteUrlDialog = onOpenRemoteUrlDialog,
            onOpenPatDialog = onOpenPatDialog,
        )
        GitSyncAuthorPreferences(
            state = state,
            onOpenAuthorNameDialog = onOpenAuthorNameDialog,
            onOpenAuthorEmailDialog = onOpenAuthorEmailDialog,
        )
        GitSyncBehaviorPreferences(
            state = state,
            syncIntervalLabel = syncIntervalLabel,
            onToggleAutoSync = onToggleAutoSync,
            onOpenSyncIntervalDialog = onOpenSyncIntervalDialog,
            onToggleSyncOnRefresh = onToggleSyncOnRefresh,
        )
        GitSyncActionPreferences(
            syncNowSubtitle = syncNowSubtitle,
            connectionSubtitle = connectionSubtitle,
            onSyncNow = onSyncNow,
            onTestConnection = onTestConnection,
            onOpenResetDialog = onOpenResetDialog,
        )
    }
}

@Composable
private fun GitSyncConnectionPreferences(
    state: GitSectionState,
    onOpenRemoteUrlDialog: () -> Unit,
    onOpenPatDialog: () -> Unit,
) {
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
}

@Composable
private fun GitSyncAuthorPreferences(
    state: GitSectionState,
    onOpenAuthorNameDialog: () -> Unit,
    onOpenAuthorEmailDialog: () -> Unit,
) {
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
}

@Composable
private fun GitSyncBehaviorPreferences(
    state: GitSectionState,
    syncIntervalLabel: String,
    onToggleAutoSync: (Boolean) -> Unit,
    onOpenSyncIntervalDialog: () -> Unit,
    onToggleSyncOnRefresh: (Boolean) -> Unit,
) {
    SettingsDivider()
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_git_auto_sync),
        subtitle = stringResource(R.string.settings_git_auto_sync_subtitle),
        icon = Icons.Outlined.Schedule,
        checked = state.autoSyncEnabled,
        onCheckedChange = onToggleAutoSync,
    )
    GitAutoSyncIntervalPreference(
        autoSyncEnabled = state.autoSyncEnabled,
        syncIntervalLabel = syncIntervalLabel,
        onOpenSyncIntervalDialog = onOpenSyncIntervalDialog,
    )
    SettingsDivider()
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_git_sync_on_refresh),
        subtitle = stringResource(R.string.settings_git_sync_on_refresh_subtitle),
        icon = Icons.Outlined.Refresh,
        checked = state.syncOnRefreshEnabled,
        onCheckedChange = onToggleSyncOnRefresh,
    )
}

@Composable
private fun GitSyncActionPreferences(
    syncNowSubtitle: String,
    connectionSubtitle: String,
    onSyncNow: () -> Unit,
    onTestConnection: () -> Unit,
    onOpenResetDialog: () -> Unit,
) {
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

@Composable
private fun GitAutoSyncIntervalPreference(
    autoSyncEnabled: Boolean,
    syncIntervalLabel: String,
    onOpenSyncIntervalDialog: () -> Unit,
) {
    SettingsExpandableContent(
        visible = autoSyncEnabled,
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
}
