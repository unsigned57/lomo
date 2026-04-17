package com.lomo.app.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SettingsGroup
import com.lomo.ui.component.settings.SwitchPreferenceItem

@Composable
fun SyncSettingsSection(
    storageState: StorageSectionState,
    gitContent: @Composable () -> Unit,
    webDavContent: @Composable () -> Unit,
    s3Content: @Composable () -> Unit,
    onToggleSyncInbox: (Boolean) -> Unit,
    onSelectSyncInbox: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_sync)) {
        SyncInboxSettingsSection(
            state = storageState,
            onToggleEnabled = onToggleSyncInbox,
            onSelectDirectory = onSelectSyncInbox,
        )
        SettingsDivider()
        gitContent()
        SettingsDivider()
        webDavContent()
        SettingsDivider()
        s3Content()
    }
}

@Composable
private fun SyncInboxSettingsSection(
    state: StorageSectionState,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectDirectory: () -> Unit,
) {
    val policy = SyncInboxSectionPolicies.resolve(enabled = state.syncInboxEnabled)
    if (!policy.showSectionHeader) {
        return
    }
    val notSetLabel = stringResource(R.string.settings_not_set)
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_sync_inbox_enabled),
        subtitle = stringResource(R.string.settings_sync_inbox_enabled_subtitle),
        icon = Icons.Outlined.Sync,
        checked = state.syncInboxEnabled,
        onCheckedChange = onToggleEnabled,
    )
    SettingsExpandableContent(
        visible = policy.showDirectoryPreference,
        label = "SyncInboxDirectoryVisibility",
    ) {
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_sync_inbox_directory),
            subtitle = state.syncInboxDirectory.subtitle(notSetLabel),
            icon = Icons.Default.Folder,
            enabled = policy.headerInteractive,
            onClick = onSelectDirectory,
        )
    }
}
