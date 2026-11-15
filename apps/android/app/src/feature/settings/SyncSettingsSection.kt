package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SettingsGroup
import com.lomo.ui.component.settings.SettingsGroupScope
import com.lomo.ui.component.settings.SwitchPreferenceItem
import com.lomo.ui.icon.LomoIcons

@Composable
fun SyncSettingsSection(
    storageState: StorageSectionState,
    gitContent: SettingsGroupScope.() -> Unit,
    webDavContent: SettingsGroupScope.() -> Unit,
    s3Content: SettingsGroupScope.() -> Unit,
    onToggleSyncInbox: (Boolean) -> Unit,
    onSelectSyncInbox: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSyncCenter: (() -> Unit)? = null,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_sync), modifier = modifier) {
        if (onOpenSyncCenter != null) {
            item {
                PreferenceItem(
                    title = stringResource(R.string.sync_center_title),
                    subtitle = stringResource(R.string.sync_center_open_conflicts),
                    icon = LomoIcons.SyncProblem,
                    onClick = onOpenSyncCenter,
                    modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.SETTINGS_SYNC_CENTER),
                )
            }
        }
        syncInboxSettingsSection(
            state = storageState,
            onToggleEnabled = onToggleSyncInbox,
            onSelectDirectory = onSelectSyncInbox,
        )
        gitContent()
        webDavContent()
        s3Content()
    }
}

private fun SettingsGroupScope.syncInboxSettingsSection(
    state: StorageSectionState,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectDirectory: () -> Unit,
) {
    val policy = SyncInboxSectionPolicies.resolve(enabled = state.syncInboxEnabled)
    if (!policy.showSectionHeader) {
        return
    }
    item {
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_sync_inbox_enabled),
            subtitle = stringResource(R.string.settings_sync_inbox_enabled_subtitle),
            icon = LomoIcons.Inbox,
            checked = state.syncInboxEnabled,
            onCheckedChange = onToggleEnabled,
        )
    }
    expandable(
        visible = policy.showDirectoryPreference,
        label = "SyncInboxDirectoryVisibility",
    ) {
        item {
            val notSetLabel = stringResource(R.string.settings_not_set)
            PreferenceItem(
                title = stringResource(R.string.settings_sync_inbox_directory),
                subtitle = state.syncInboxDirectory.subtitle(notSetLabel),
                icon = LomoIcons.FolderOpen,
                enabled = policy.headerInteractive,
                onClick = onSelectDirectory,
            )
        }
    }
}
