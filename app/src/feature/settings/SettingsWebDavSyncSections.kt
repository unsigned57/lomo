package com.lomo.app.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.WebDavProvider
import com.lomo.ui.component.settings.PreferenceItem

data class WebDavSyncSectionLabels(
    val common: RemoteProviderSectionLabels,
    val provider: String,
)

data class WebDavSyncDialogActions(
    val openProvider: () -> Unit,
    val openBaseUrl: () -> Unit,
    val openEndpointUrl: () -> Unit,
    val openUsername: () -> Unit,
    val openPassword: () -> Unit,
)

@Composable
fun WebDavSyncSettingsSection(
    state: WebDavSectionState,
    labels: WebDavSyncSectionLabels,
    dialogs: WebDavSyncDialogActions,
    actions: RemoteProviderSectionActions,
    modifier: Modifier = Modifier,
) {
    RemoteProviderSectionSurface(
        providerSettings = state.providerSettings,
        labels = labels.common,
        actions = actions,
        modifier = modifier,
        providerSettingsContent = {
            WebDavAccountPreferences(
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
    val providerSettings = state.providerSettings
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
            credentialStatusSubtitle(
                status = providerSettings.credentialStatus(RemoteProviderCredentialField.WebDavPassword),
                configuredResId = R.string.settings_webdav_password_configured,
                missingResId = R.string.settings_webdav_password_not_set,
            ),
        icon = Icons.Outlined.Lock,
        onClick = onOpenPasswordDialog,
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
