package com.lomo.app.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.SyncBackendType
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SwitchPreferenceItem

data class RemoteProviderSectionLabels(
    val syncInterval: String,
)

data class RemoteProviderSectionActions(
    val provider: SyncBackendType,
    val toggleEnabled: (Boolean) -> Unit,
    val toggleAutoSync: (Boolean) -> Unit,
    val openSyncInterval: () -> Unit,
    val toggleSyncOnRefresh: (Boolean) -> Unit,
    val syncNow: () -> Unit,
    val testConnection: () -> Unit,
)

private data class RemoteProviderSectionPresentation(
    @StringRes val enableTitleResId: Int,
    @StringRes val enableSubtitleResId: Int,
    @StringRes val autoSyncTitleResId: Int,
    @StringRes val autoSyncSubtitleResId: Int,
    @StringRes val syncIntervalTitleResId: Int,
    @StringRes val syncOnRefreshTitleResId: Int,
    @StringRes val syncOnRefreshSubtitleResId: Int,
    @StringRes val syncNowTitleResId: Int,
    @StringRes val testConnectionTitleResId: Int,
    val advancedVisibilityLabel: String,
    val syncIntervalVisibilityLabel: String,
)

@Composable
internal fun RemoteProviderSectionSurface(
    providerSettings: RemoteProviderSettingsModel,
    labels: RemoteProviderSectionLabels,
    actions: RemoteProviderSectionActions,
    providerSettingsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    providerActionContent: (@Composable () -> Unit)?,
) {
    require(actions.provider == providerSettings.provider) {
        "Provider section actions for ${actions.provider} cannot render ${providerSettings.provider}"
    }
    val presentation = providerSettings.provider.sectionPresentation()
    Column(modifier = modifier) {
        SwitchPreferenceItem(
            title = stringResource(presentation.enableTitleResId),
            subtitle = stringResource(presentation.enableSubtitleResId),
            icon = Icons.Outlined.Sync,
            checked = providerSettings.enabled,
            onCheckedChange = actions.toggleEnabled,
        )
        SettingsExpandableContent(
            visible = providerSettings.enabled,
            label = presentation.advancedVisibilityLabel,
        ) {
            Column {
                providerSettingsContent()
                RemoteProviderBehaviorPreferences(
                    providerSettings = providerSettings,
                    labels = labels,
                    actions = actions,
                    presentation = presentation,
                )
                RemoteProviderActionPreferences(
                    providerSettings = providerSettings,
                    actions = actions,
                    presentation = presentation,
                )
                providerActionContent?.invoke()
            }
        }
    }
}

@Composable
private fun RemoteProviderBehaviorPreferences(
    providerSettings: RemoteProviderSettingsModel,
    labels: RemoteProviderSectionLabels,
    actions: RemoteProviderSectionActions,
    presentation: RemoteProviderSectionPresentation,
) {
    SettingsDivider()
    SwitchPreferenceItem(
        title = stringResource(presentation.autoSyncTitleResId),
        subtitle = stringResource(presentation.autoSyncSubtitleResId),
        icon = Icons.Outlined.Schedule,
        checked = providerSettings.autoSyncEnabled,
        onCheckedChange = actions.toggleAutoSync,
    )
    RemoteProviderSyncIntervalPreference(
        providerSettings = providerSettings,
        labels = labels,
        actions = actions,
        presentation = presentation,
    )
    SettingsDivider()
    SwitchPreferenceItem(
        title = stringResource(presentation.syncOnRefreshTitleResId),
        subtitle = stringResource(presentation.syncOnRefreshSubtitleResId),
        icon = Icons.Outlined.Refresh,
        checked = providerSettings.syncOnRefreshEnabled,
        onCheckedChange = actions.toggleSyncOnRefresh,
    )
}

@Composable
private fun RemoteProviderSyncIntervalPreference(
    providerSettings: RemoteProviderSettingsModel,
    labels: RemoteProviderSectionLabels,
    actions: RemoteProviderSectionActions,
    presentation: RemoteProviderSectionPresentation,
) {
    SettingsExpandableContent(
        visible = providerSettings.autoSyncEnabled,
        label = presentation.syncIntervalVisibilityLabel,
    ) {
        Column {
            SettingsDivider()
            PreferenceItem(
                title = stringResource(presentation.syncIntervalTitleResId),
                subtitle = labels.syncInterval,
                icon = Icons.Outlined.Schedule,
                onClick = actions.openSyncInterval,
            )
        }
    }
}

@Composable
private fun RemoteProviderActionPreferences(
    providerSettings: RemoteProviderSettingsModel,
    actions: RemoteProviderSectionActions,
    presentation: RemoteProviderSectionPresentation,
) {
    SettingsDivider()
    PreferenceItem(
        title = stringResource(presentation.syncNowTitleResId),
        subtitle =
            unifiedSyncNowSubtitle(
                provider = providerSettings.provider,
                state = providerSettings.syncState,
                lastSyncTime = providerSettings.lastSyncTime,
            ),
        icon = Icons.Outlined.Sync,
        onClick = actions.syncNow,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(presentation.testConnectionTitleResId),
        subtitle =
            connectionTestSubtitle(
                provider = providerSettings.provider,
                state = providerSettings.connectionTestState,
            ),
        icon = Icons.Outlined.Link,
        onClick = actions.testConnection,
    )
}

private fun SyncBackendType.sectionPresentation(): RemoteProviderSectionPresentation =
    when (this) {
        SyncBackendType.GIT ->
            RemoteProviderSectionPresentation(
                enableTitleResId = R.string.settings_git_sync_enable,
                enableSubtitleResId = R.string.settings_git_sync_enable_subtitle,
                autoSyncTitleResId = R.string.settings_git_auto_sync,
                autoSyncSubtitleResId = R.string.settings_git_auto_sync_subtitle,
                syncIntervalTitleResId = R.string.settings_git_sync_interval,
                syncOnRefreshTitleResId = R.string.settings_git_sync_on_refresh,
                syncOnRefreshSubtitleResId = R.string.settings_git_sync_on_refresh_subtitle,
                syncNowTitleResId = R.string.settings_git_sync_now,
                testConnectionTitleResId = R.string.settings_git_test_connection,
                advancedVisibilityLabel = "GitSyncAdvancedVisibility",
                syncIntervalVisibilityLabel = "GitAutoSyncIntervalVisibility",
            )

        SyncBackendType.WEBDAV ->
            RemoteProviderSectionPresentation(
                enableTitleResId = R.string.settings_webdav_sync_enable,
                enableSubtitleResId = R.string.settings_webdav_sync_enable_subtitle,
                autoSyncTitleResId = R.string.settings_webdav_auto_sync,
                autoSyncSubtitleResId = R.string.settings_webdav_auto_sync_subtitle,
                syncIntervalTitleResId = R.string.settings_webdav_sync_interval,
                syncOnRefreshTitleResId = R.string.settings_webdav_sync_on_refresh,
                syncOnRefreshSubtitleResId = R.string.settings_webdav_sync_on_refresh_subtitle,
                syncNowTitleResId = R.string.settings_webdav_sync_now,
                testConnectionTitleResId = R.string.settings_webdav_test_connection,
                advancedVisibilityLabel = "WebDavSyncAdvancedVisibility",
                syncIntervalVisibilityLabel = "WebDavAutoSyncIntervalVisibility",
            )

        SyncBackendType.S3 ->
            RemoteProviderSectionPresentation(
                enableTitleResId = R.string.settings_s3_sync_enable,
                enableSubtitleResId = R.string.settings_s3_sync_enable_subtitle,
                autoSyncTitleResId = R.string.settings_s3_auto_sync,
                autoSyncSubtitleResId = R.string.settings_s3_auto_sync_subtitle,
                syncIntervalTitleResId = R.string.settings_s3_sync_interval,
                syncOnRefreshTitleResId = R.string.settings_s3_sync_on_refresh,
                syncOnRefreshSubtitleResId = R.string.settings_s3_sync_on_refresh_subtitle,
                syncNowTitleResId = R.string.settings_s3_sync_now,
                testConnectionTitleResId = R.string.settings_s3_test_connection,
                advancedVisibilityLabel = "S3SyncAdvancedVisibility",
                syncIntervalVisibilityLabel = "S3AutoSyncIntervalVisibility",
            )

        SyncBackendType.INBOX,
        SyncBackendType.NONE,
        -> error("Provider $this does not own a remote settings section")
    }
