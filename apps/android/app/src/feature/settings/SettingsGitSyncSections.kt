package com.lomo.app.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SettingsGroupScope

internal fun SettingsGroupScope.gitSyncSettingsSection(
    state: GitSectionState,
    labels: RemoteProviderSectionLabels,
    dialogs: GitSyncDialogActions,
    actions: RemoteProviderSectionActions,
) {
    remoteProviderSection(
        providerSettings = state.providerSettings,
        labels = labels,
        actions = actions,
        providerSettingsContent = {
            gitSyncConnectionPreferences(
                state = state,
                onOpenRemoteUrlDialog = dialogs.openRemoteUrl,
                onOpenBranchDialog = dialogs.openBranch,
                onOpenPatDialog = dialogs.openPat,
            )
            gitSyncAuthorPreferences(
                state = state,
                onOpenAuthorNameDialog = dialogs.openAuthorName,
                onOpenAuthorEmailDialog = dialogs.openAuthorEmail,
            )
        },
        providerActionContent = {
            item {
                PreferenceItem(
                    title = stringResource(R.string.settings_git_reset_repo),
                    subtitle = stringResource(R.string.settings_git_reset_repo_subtitle),
                    icon = Icons.Outlined.DeleteForever,
                    onClick = dialogs.openReset,
                )
            }
        },
    )
}

private fun SettingsGroupScope.gitSyncConnectionPreferences(
    state: GitSectionState,
    onOpenRemoteUrlDialog: () -> Unit,
    onOpenBranchDialog: () -> Unit,
    onOpenPatDialog: () -> Unit,
) {
    val providerSettings = state.providerSettings
    item {
        PreferenceItem(
            title = stringResource(R.string.settings_git_remote_url),
            subtitle = state.remoteUrl.ifBlank { stringResource(R.string.settings_not_set) },
            icon = Icons.Outlined.Link,
            onClick = onOpenRemoteUrlDialog,
        )
    }
    item {
        PreferenceItem(
            title = stringResource(R.string.settings_git_branch),
            subtitle = state.branch.ifBlank { stringResource(R.string.settings_not_set) },
            icon = Icons.Outlined.AccountTree,
            onClick = onOpenBranchDialog,
        )
    }
    item {
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
}

private fun SettingsGroupScope.gitSyncAuthorPreferences(
    state: GitSectionState,
    onOpenAuthorNameDialog: () -> Unit,
    onOpenAuthorEmailDialog: () -> Unit,
) {
    item {
        PreferenceItem(
            title = stringResource(R.string.settings_git_author_name),
            subtitle = state.authorName.ifBlank { stringResource(R.string.settings_not_set) },
            icon = Icons.Outlined.Person,
            onClick = onOpenAuthorNameDialog,
        )
    }
    item {
        PreferenceItem(
            title = stringResource(R.string.settings_git_author_email),
            subtitle = state.authorEmail.ifBlank { stringResource(R.string.settings_not_set) },
            icon = Icons.Outlined.Email,
            onClick = onOpenAuthorEmailDialog,
        )
    }
}
