package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.ui.component.settings.PreferenceItem

@Composable
fun GitSyncSettingsSection(
    state: GitSectionState,
    labels: RemoteProviderSectionLabels,
    dialogs: GitSyncDialogActions,
    actions: RemoteProviderSectionActions,
    modifier: Modifier = Modifier,
) {
    RemoteProviderSectionSurface(
        providerSettings = state.providerSettings,
        labels = labels,
        actions = actions,
        modifier = modifier,
        providerSettingsContent = {
            Column {
                GitSyncConnectionPreferences(
                    state = state,
                    onOpenRemoteUrlDialog = dialogs.openRemoteUrl,
                    onOpenPatDialog = dialogs.openPat,
                )
                GitSyncAuthorPreferences(
                    state = state,
                    onOpenAuthorNameDialog = dialogs.openAuthorName,
                    onOpenAuthorEmailDialog = dialogs.openAuthorEmail,
                )
            }
        },
        providerActionContent = {
            GitSyncResetRepositoryPreference(onOpenResetDialog = dialogs.openReset)
        },
    )
}

@Composable
private fun GitSyncConnectionPreferences(
    state: GitSectionState,
    onOpenRemoteUrlDialog: () -> Unit,
    onOpenPatDialog: () -> Unit,
) {
    val providerSettings = state.providerSettings
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
            credentialStatusSubtitle(
                status = providerSettings.credentialStatus(RemoteProviderCredentialField.GitPat),
                configuredResId = R.string.settings_git_pat_configured,
                missingResId = R.string.settings_git_pat_not_set,
            ),
        icon = Icons.Outlined.Lock,
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
private fun GitSyncResetRepositoryPreference(
    onOpenResetDialog: () -> Unit,
) {
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_git_reset_repo),
        subtitle = stringResource(R.string.settings_git_reset_repo_subtitle),
        icon = Icons.Outlined.DeleteForever,
        onClick = onOpenResetDialog,
    )
}
