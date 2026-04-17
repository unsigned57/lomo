package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.WebDavProvider
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SwitchPreferenceItem

@Composable
fun WebDavSyncSettingsSection(
    state: WebDavSectionState,
    providerLabel: String,
    syncIntervalLabel: String,
    syncNowSubtitle: String,
    connectionSubtitle: String,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenProviderDialog: () -> Unit,
    onOpenBaseUrlDialog: () -> Unit,
    onOpenEndpointUrlDialog: () -> Unit,
    onOpenUsernameDialog: () -> Unit,
    onOpenPasswordDialog: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onOpenSyncIntervalDialog: () -> Unit,
    onToggleSyncOnRefresh: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onTestConnection: () -> Unit,
) {
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_webdav_sync_enable),
        subtitle = stringResource(R.string.settings_webdav_sync_enable_subtitle),
        icon = Icons.Outlined.Sync,
        checked = state.enabled,
        onCheckedChange = onToggleEnabled,
    )
    SettingsExpandableContent(
        visible = state.enabled,
        label = "WebDavSyncAdvancedVisibility",
    ) {
        WebDavSyncAdvancedContent(
            state = state,
            providerLabel = providerLabel,
            syncIntervalLabel = syncIntervalLabel,
            syncNowSubtitle = syncNowSubtitle,
            connectionSubtitle = connectionSubtitle,
            onOpenProviderDialog = onOpenProviderDialog,
            onOpenBaseUrlDialog = onOpenBaseUrlDialog,
            onOpenEndpointUrlDialog = onOpenEndpointUrlDialog,
            onOpenUsernameDialog = onOpenUsernameDialog,
            onOpenPasswordDialog = onOpenPasswordDialog,
            onToggleAutoSync = onToggleAutoSync,
            onOpenSyncIntervalDialog = onOpenSyncIntervalDialog,
            onToggleSyncOnRefresh = onToggleSyncOnRefresh,
            onSyncNow = onSyncNow,
            onTestConnection = onTestConnection,
        )
    }
}

@Composable
private fun WebDavSyncAdvancedContent(
    state: WebDavSectionState,
    providerLabel: String,
    syncIntervalLabel: String,
    syncNowSubtitle: String,
    connectionSubtitle: String,
    onOpenProviderDialog: () -> Unit,
    onOpenBaseUrlDialog: () -> Unit,
    onOpenEndpointUrlDialog: () -> Unit,
    onOpenUsernameDialog: () -> Unit,
    onOpenPasswordDialog: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onOpenSyncIntervalDialog: () -> Unit,
    onToggleSyncOnRefresh: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onTestConnection: () -> Unit,
) {
    Column {
        WebDavAccountPreferences(
            state = state,
            providerLabel = providerLabel,
            onOpenProviderDialog = onOpenProviderDialog,
            onOpenBaseUrlDialog = onOpenBaseUrlDialog,
            onOpenEndpointUrlDialog = onOpenEndpointUrlDialog,
            onOpenUsernameDialog = onOpenUsernameDialog,
            onOpenPasswordDialog = onOpenPasswordDialog,
        )
        WebDavBehaviorPreferences(
            state = state,
            syncIntervalLabel = syncIntervalLabel,
            onToggleAutoSync = onToggleAutoSync,
            onOpenSyncIntervalDialog = onOpenSyncIntervalDialog,
            onToggleSyncOnRefresh = onToggleSyncOnRefresh,
        )
        WebDavActionPreferences(
            syncNowSubtitle = syncNowSubtitle,
            connectionSubtitle = connectionSubtitle,
            onSyncNow = onSyncNow,
            onTestConnection = onTestConnection,
        )
    }
}

@Composable
private fun WebDavAccountPreferences(
    state: WebDavSectionState,
    providerLabel: String,
    onOpenProviderDialog: () -> Unit,
    onOpenBaseUrlDialog: () -> Unit,
    onOpenEndpointUrlDialog: () -> Unit,
    onOpenUsernameDialog: () -> Unit,
    onOpenPasswordDialog: () -> Unit,
) {
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_webdav_provider),
        subtitle = providerLabel,
        icon = Icons.Outlined.Link,
        onClick = onOpenProviderDialog,
    )
    SettingsDivider()
    WebDavEndpointSettings(
        state = state,
        onOpenBaseUrlDialog = onOpenBaseUrlDialog,
        onOpenEndpointUrlDialog = onOpenEndpointUrlDialog,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_webdav_username),
        subtitle = state.username.ifBlank { stringResource(R.string.settings_not_set) },
        icon = Icons.Outlined.Person,
        onClick = onOpenUsernameDialog,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_webdav_password),
        subtitle =
            stringResource(
                if (state.passwordConfigured) {
                    R.string.settings_webdav_password_configured
                } else {
                    R.string.settings_webdav_password_not_set
                },
            ),
        icon = Icons.Default.Lock,
        onClick = onOpenPasswordDialog,
    )
}

@Composable
private fun WebDavBehaviorPreferences(
    state: WebDavSectionState,
    syncIntervalLabel: String,
    onToggleAutoSync: (Boolean) -> Unit,
    onOpenSyncIntervalDialog: () -> Unit,
    onToggleSyncOnRefresh: (Boolean) -> Unit,
) {
    SettingsDivider()
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_webdav_auto_sync),
        subtitle = stringResource(R.string.settings_webdav_auto_sync_subtitle),
        icon = Icons.Outlined.Schedule,
        checked = state.autoSyncEnabled,
        onCheckedChange = onToggleAutoSync,
    )
    WebDavAutoSyncIntervalPreference(
        autoSyncEnabled = state.autoSyncEnabled,
        syncIntervalLabel = syncIntervalLabel,
        onOpenSyncIntervalDialog = onOpenSyncIntervalDialog,
    )
    SettingsDivider()
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_webdav_sync_on_refresh),
        subtitle = stringResource(R.string.settings_webdav_sync_on_refresh_subtitle),
        icon = Icons.Outlined.Refresh,
        checked = state.syncOnRefreshEnabled,
        onCheckedChange = onToggleSyncOnRefresh,
    )
}

@Composable
private fun WebDavActionPreferences(
    syncNowSubtitle: String,
    connectionSubtitle: String,
    onSyncNow: () -> Unit,
    onTestConnection: () -> Unit,
) {
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_webdav_sync_now),
        subtitle = syncNowSubtitle,
        icon = Icons.Outlined.Sync,
        onClick = onSyncNow,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_webdav_test_connection),
        subtitle = connectionSubtitle,
        icon = Icons.Outlined.Link,
        onClick = onTestConnection,
    )
}

@Composable
private fun WebDavEndpointSettings(
    state: WebDavSectionState,
    onOpenBaseUrlDialog: () -> Unit,
    onOpenEndpointUrlDialog: () -> Unit,
) {
    when (state.provider) {
        WebDavProvider.NEXTCLOUD -> {
            PreferenceItem(
                title = stringResource(R.string.settings_webdav_base_url),
                subtitle = state.baseUrl.ifBlank { stringResource(R.string.settings_not_set) },
                icon = Icons.Outlined.Link,
                onClick = onOpenBaseUrlDialog,
            )
        }

        WebDavProvider.NUTSTORE,
        WebDavProvider.CUSTOM,
        -> {
            PreferenceItem(
                title = stringResource(R.string.settings_webdav_endpoint_url),
                subtitle = state.endpointUrl.ifBlank { stringResource(R.string.settings_not_set) },
                icon = Icons.Outlined.Link,
                onClick = onOpenEndpointUrlDialog,
            )
        }
    }
}

@Composable
private fun WebDavAutoSyncIntervalPreference(
    autoSyncEnabled: Boolean,
    syncIntervalLabel: String,
    onOpenSyncIntervalDialog: () -> Unit,
) {
    SettingsExpandableContent(
        visible = autoSyncEnabled,
        label = "WebDavAutoSyncIntervalVisibility",
    ) {
        Column {
            SettingsDivider()
            PreferenceItem(
                title = stringResource(R.string.settings_webdav_sync_interval),
                subtitle = syncIntervalLabel,
                icon = Icons.Outlined.Schedule,
                onClick = onOpenSyncIntervalDialog,
            )
        }
    }
}
