package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun GitDialogs(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    val gitFeature = features.git
    GitRemoteUrlDialog(
        gitFeature = gitFeature,
        dialogState = dialogState,
    )
    GitPatDialog(
        gitFeature = gitFeature,
        dialogState = dialogState,
    )
    GitAuthorNameDialog(
        gitFeature = gitFeature,
        dialogState = dialogState,
    )
    GitAuthorEmailDialog(
        gitFeature = gitFeature,
        dialogState = dialogState,
    )
    SelectionDialogIfVisible(
        visible = dialogState.showGitSyncIntervalDialog,
        title = stringResource(R.string.settings_git_select_sync_interval),
        options = options.gitSyncIntervals.toImmutableList(),
        currentSelection = uiState.git.autoSyncInterval,
        onDismiss = { dialogState.showGitSyncIntervalDialog = false },
        onSelect = {
            gitFeature.updateGitAutoSyncInterval(it)
            dialogState.showGitSyncIntervalDialog = false
        },
        labelProvider = { options.gitSyncIntervalLabels[it] ?: it },
    )
    GitResetConfirmDialog(
        uiState = uiState,
        gitFeature = gitFeature,
        dialogState = dialogState,
    )
    GitConflictResolutionDialog(
        gitFeature = gitFeature,
        dialogState = dialogState,
    )
}

@Composable
private fun GitRemoteUrlDialog(
    gitFeature: SettingsGitFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showGitRemoteUrlDialog) {
        return
    }
    val isUrlValid = gitFeature.isValidGitRemoteUrl(dialogState.gitRemoteUrlInput)
    AlertDialog(
        onDismissRequest = { dialogState.showGitRemoteUrlDialog = false },
        title = { Text(stringResource(R.string.settings_git_remote_url)) },
        text = {
            OutlinedTextField(
                value = dialogState.gitRemoteUrlInput,
                onValueChange = { dialogState.gitRemoteUrlInput = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_git_remote_url_hint)) },
                isError = dialogState.gitRemoteUrlInput.isNotBlank() && !isUrlValid,
                supportingText =
                    if (dialogState.gitRemoteUrlInput.isNotBlank() && !isUrlValid) {
                        { Text(stringResource(R.string.settings_git_remote_url_error_https_required)) }
                    } else {
                        null
                    },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    gitFeature.updateGitRemoteUrl(dialogState.gitRemoteUrlInput.trim())
                    dialogState.showGitRemoteUrlDialog = false
                },
                enabled = isUrlValid,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showGitRemoteUrlDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun GitPatDialog(
    gitFeature: SettingsGitFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showGitPatDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showGitPatDialog = false },
        title = { Text(stringResource(R.string.settings_git_pat_dialog_title)) },
        text = { GitPatDialogContent(dialogState = dialogState) },
        confirmButton = {
            TextButton(
                onClick = {
                    gitFeature.updateGitPat(dialogState.gitPatInput.trim())
                    dialogState.showGitPatDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showGitPatDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun GitPatDialogContent(dialogState: SettingsDialogState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_git_pat_dialog_message),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = dialogState.gitPatInput,
            onValueChange = { dialogState.gitPatInput = it },
            singleLine = true,
            label = { Text(stringResource(R.string.settings_git_pat_hint)) },
            visualTransformation =
                if (dialogState.gitPatVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            trailingIcon = {
                TextButton(onClick = { dialogState.gitPatVisible = !dialogState.gitPatVisible }) {
                    Text(
                        text =
                            if (dialogState.gitPatVisible) {
                                stringResource(R.string.share_password_hide)
                            } else {
                                stringResource(R.string.share_password_show)
                            },
                    )
                }
            },
        )
    }
}

@Composable
private fun GitAuthorNameDialog(
    gitFeature: SettingsGitFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showGitAuthorNameDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showGitAuthorNameDialog = false },
        title = { Text(stringResource(R.string.settings_git_author_name)) },
        text = {
            OutlinedTextField(
                value = dialogState.gitAuthorNameInput,
                onValueChange = { dialogState.gitAuthorNameInput = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_git_author_name_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    gitFeature.updateGitAuthorName(dialogState.gitAuthorNameInput.trim())
                    dialogState.showGitAuthorNameDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showGitAuthorNameDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun GitAuthorEmailDialog(
    gitFeature: SettingsGitFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showGitAuthorEmailDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showGitAuthorEmailDialog = false },
        title = { Text(stringResource(R.string.settings_git_author_email)) },
        text = {
            OutlinedTextField(
                value = dialogState.gitAuthorEmailInput,
                onValueChange = { dialogState.gitAuthorEmailInput = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_git_author_email_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    gitFeature.updateGitAuthorEmail(dialogState.gitAuthorEmailInput.trim())
                    dialogState.showGitAuthorEmailDialog = false
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showGitAuthorEmailDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun GitResetConfirmDialog(
    uiState: SettingsScreenUiState,
    gitFeature: SettingsGitFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    if (!dialogState.showGitResetConfirmDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = { dialogState.showGitResetConfirmDialog = false },
        title = { Text(stringResource(R.string.settings_git_reset_repo_confirm_title)) },
        text = { Text(stringResource(R.string.settings_git_reset_repo_confirm_message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    gitFeature.resetGitRepository()
                    dialogState.showGitResetConfirmDialog = false
                },
                enabled = !uiState.git.resetInProgress,
            ) {
                Text(stringResource(R.string.action_reset))
            }
        },
        dismissButton = {
            TextButton(onClick = { dialogState.showGitResetConfirmDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun GitConflictResolutionDialog(
    gitFeature: SettingsGitFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    val conflictError = dialogState.gitConflictError
    if (!dialogState.showGitConflictResolutionDialog || conflictError == null) {
        return
    }
    AlertDialog(
        onDismissRequest = {
            dialogState.showGitConflictResolutionDialog = false
            dialogState.gitConflictError = null
        },
        title = { Text(stringResource(R.string.settings_git_conflict_dialog_title)) },
        text = {
            Text(
                stringResource(
                    R.string.settings_git_conflict_dialog_message,
                    SettingsErrorPresenter.gitSyncErrorMessage(conflictError.code, conflictError.detail),
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    gitFeature.resolveGitConflictUsingLocal()
                    dialogState.showGitConflictResolutionDialog = false
                    dialogState.gitConflictError = null
                },
            ) {
                Text(stringResource(R.string.settings_git_conflict_keep_local))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    gitFeature.resolveGitConflictUsingRemote()
                    dialogState.showGitConflictResolutionDialog = false
                    dialogState.gitConflictError = null
                },
            ) {
                Text(stringResource(R.string.settings_git_conflict_use_remote))
            }
        },
    )
}
