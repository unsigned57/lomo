package com.lomo.app.feature.settings

import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.WebDavProvider
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SettingsGroupScope
import com.lomo.ui.icon.LomoIcons

data class WebDavSyncSectionLabels(val common: RemoteProviderSectionLabels, val provider: String)

data class WebDavSyncDialogActions(
    val openProvider: () -> Unit,
    val openBaseUrl: () -> Unit,
    val openEndpointUrl: () -> Unit,
    val openUsername: () -> Unit,
    val openPassword: () -> Unit,
)

internal fun SettingsGroupScope.webDavSyncSettingsSection(
    state: WebDavSectionState,
    labels: WebDavSyncSectionLabels,
    dialogs: WebDavSyncDialogActions,
    actions: RemoteProviderSectionActions,
) {
    remoteProviderSection(
        providerSettings = state.providerSettings,
        labels = labels.common,
        actions = actions,
        providerSettingsContent = {
            webDavAccountPreferences(
                state = state,
                providerLabel = labels.provider,
                onOpenProviderDialog = dialogs.openProvider,
                onOpenBaseUrlDialog = dialogs.openBaseUrl,
                onOpenEndpointUrlDialog = dialogs.openEndpointUrl,
                onOpenUsernameDialog = dialogs.openUsername,
                onOpenPasswordDialog = dialogs.openPassword,
            )
        },
        providerActionContent = null,
    )
}

private fun SettingsGroupScope.webDavAccountPreferences(
    state: WebDavSectionState,
    providerLabel: String,
    onOpenProviderDialog: () -> Unit,
    onOpenBaseUrlDialog: () -> Unit,
    onOpenEndpointUrlDialog: () -> Unit,
    onOpenUsernameDialog: () -> Unit,
    onOpenPasswordDialog: () -> Unit,
) {
    val providerSettings = state.providerSettings
    item {
        PreferenceItem(
            title = stringResource(R.string.settings_webdav_provider),
            subtitle = providerLabel,
            icon = LomoIcons.Link,
            onClick = onOpenProviderDialog,
        )
    }
    webDavEndpointSettings(
        state = state,
        onOpenBaseUrlDialog = onOpenBaseUrlDialog,
        onOpenEndpointUrlDialog = onOpenEndpointUrlDialog,
    )
    item {
        PreferenceItem(
            title = stringResource(R.string.settings_webdav_username),
            subtitle = state.username.ifBlank { stringResource(R.string.settings_not_set) },
            icon = LomoIcons.Person,
            onClick = onOpenUsernameDialog,
        )
    }
    item {
        PreferenceItem(
            title = stringResource(R.string.settings_webdav_password),
            subtitle =
            credentialStatusSubtitle(
                status = providerSettings.credentialStatus(RemoteProviderCredentialField.WebDavPassword),
                configuredResId = R.string.settings_webdav_password_configured,
                missingResId = R.string.settings_webdav_password_not_set,
            ),
            icon = LomoIcons.Lock,
            onClick = onOpenPasswordDialog,
        )
    }
}

private fun SettingsGroupScope.webDavEndpointSettings(
    state: WebDavSectionState,
    onOpenBaseUrlDialog: () -> Unit,
    onOpenEndpointUrlDialog: () -> Unit,
) {
    when (state.provider) {
        WebDavProvider.NEXTCLOUD -> {
            item {
                PreferenceItem(
                    title = stringResource(R.string.settings_webdav_base_url),
                    subtitle = state.baseUrl.ifBlank { stringResource(R.string.settings_not_set) },
                    icon = LomoIcons.Link,
                    onClick = onOpenBaseUrlDialog,
                )
            }
        }

        WebDavProvider.NUTSTORE,
        WebDavProvider.CUSTOM,
        -> {
            item {
                PreferenceItem(
                    title = stringResource(R.string.settings_webdav_endpoint_url),
                    subtitle = state.endpointUrl.ifBlank { stringResource(R.string.settings_not_set) },
                    icon = LomoIcons.Link,
                    onClick = onOpenEndpointUrlDialog,
                )
            }
        }
    }
}
