package com.lomo.app.feature.settings

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.SyncBackendType
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SettingsGroupScope
import com.lomo.ui.component.settings.SwitchPreferenceItem
import com.lomo.ui.icon.LomoIcons

data class RemoteProviderSectionLabels(val syncInterval: String)

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

/**
 * Registers one remote-provider settings block (enable switch plus its expandable advanced rows)
 * into the enclosing [SettingsGroupScope].
 */
internal fun SettingsGroupScope.remoteProviderSection(
    providerSettings: RemoteProviderSettingsModel,
    labels: RemoteProviderSectionLabels,
    actions: RemoteProviderSectionActions,
    providerSettingsContent: SettingsGroupScope.() -> Unit,
    providerActionContent: (SettingsGroupScope.() -> Unit)?,
) {
    require(actions.provider == providerSettings.provider) {
        "Provider section actions for ${actions.provider} cannot render ${providerSettings.provider}"
    }
    val presentation = providerSettings.provider.sectionPresentation()
    item {
        SwitchPreferenceItem(
            title = stringResource(presentation.enableTitleResId),
            subtitle = stringResource(presentation.enableSubtitleResId),
            icon = LomoIcons.Sync,
            checked = providerSettings.enabled,
            onCheckedChange = actions.toggleEnabled,
        )
    }
    expandable(
        visible = providerSettings.enabled,
        label = presentation.advancedVisibilityLabel,
    ) {
        providerSettingsContent()
        remoteProviderBehaviorPreferences(
            providerSettings = providerSettings,
            labels = labels,
            actions = actions,
            presentation = presentation,
        )
        remoteProviderActionPreferences(
            providerSettings = providerSettings,
            actions = actions,
            presentation = presentation,
        )
        providerActionContent?.invoke(this)
    }
}

private fun SettingsGroupScope.remoteProviderBehaviorPreferences(
    providerSettings: RemoteProviderSettingsModel,
    labels: RemoteProviderSectionLabels,
    actions: RemoteProviderSectionActions,
    presentation: RemoteProviderSectionPresentation,
) {
    item {
        SwitchPreferenceItem(
            title = stringResource(presentation.autoSyncTitleResId),
            subtitle = stringResource(presentation.autoSyncSubtitleResId),
            icon = LomoIcons.Schedule,
            checked = providerSettings.autoSyncEnabled,
            onCheckedChange = actions.toggleAutoSync,
        )
    }
    expandable(
        visible = providerSettings.autoSyncEnabled,
        label = presentation.syncIntervalVisibilityLabel,
    ) {
        item {
            PreferenceItem(
                title = stringResource(presentation.syncIntervalTitleResId),
                subtitle = labels.syncInterval,
                icon = LomoIcons.Timelapse,
                onClick = actions.openSyncInterval,
            )
        }
    }
    item {
        SwitchPreferenceItem(
            title = stringResource(presentation.syncOnRefreshTitleResId),
            subtitle = stringResource(presentation.syncOnRefreshSubtitleResId),
            icon = LomoIcons.Refresh,
            checked = providerSettings.syncOnRefreshEnabled,
            onCheckedChange = actions.toggleSyncOnRefresh,
        )
    }
}

private fun SettingsGroupScope.remoteProviderActionPreferences(
    providerSettings: RemoteProviderSettingsModel,
    actions: RemoteProviderSectionActions,
    presentation: RemoteProviderSectionPresentation,
) {
    item {
        PreferenceItem(
            title = stringResource(presentation.syncNowTitleResId),
            subtitle =
            unifiedSyncNowSubtitle(
                provider = providerSettings.provider,
                state = providerSettings.syncState,
                lastSyncTime = providerSettings.lastSyncTime,
            ),
            icon = LomoIcons.Autorenew,
            onClick = actions.syncNow,
        )
    }
    item {
        PreferenceItem(
            title = stringResource(presentation.testConnectionTitleResId),
            subtitle =
            connectionTestSubtitle(
                provider = providerSettings.provider,
                state = providerSettings.connectionTestState,
            ),
            icon = LomoIcons.Link,
            onClick = actions.testConnection,
        )
    }
}

private fun SyncBackendType.sectionPresentation(): RemoteProviderSectionPresentation = when (this) {
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
    SyncBackendType.UNKNOWN,
    -> error("Provider $this does not own a remote settings section")
}
