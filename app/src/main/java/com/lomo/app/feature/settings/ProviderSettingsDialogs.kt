package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.WebDavProvider

@Composable
internal fun ProviderSettingsDialogs(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
) {
    when (val route = dialogState.activeProviderDialogRoute) {
        is SettingsDialogRoute.RemoteProviderText ->
            ProviderTextDialog(
                route = route,
                uiState = uiState,
                features = features,
                dialogState = dialogState,
            )
        is SettingsDialogRoute.RemoteProviderSelection ->
            ProviderSelectionDialog(
                route = route,
                uiState = uiState,
                features = features,
                dialogState = dialogState,
                options = options,
            )
        is SettingsDialogRoute.RemoteProviderConfirmation ->
            ProviderConfirmationDialog(
                route = route,
                uiState = uiState,
                features = features,
                dialogState = dialogState,
            )
        is SettingsDialogRoute.RemoteProviderGitConflict ->
            GitConflictResolutionDialog(
                route = route,
                gitFeature = features.git,
                dialogState = dialogState,
            )
        null -> Unit
    }
}

@Composable
private fun ProviderTextDialog(
    route: SettingsDialogRoute.RemoteProviderText,
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
) {
    val form = dialogState.providerTextFormState
    val value = form.value
    val validation = providerTextValidation(route, value, features)
    AlertDialog(
        onDismissRequest = dialogState::dismissProviderDialog,
        title = { Text(stringResource(route.field.titleResId())) },
        text = {
            ProviderTextDialogContent(
                route = route,
                uiState = uiState,
                form = form,
                validation = validation,
                onValueChange = dialogState::updateProviderTextValue,
                onToggleVisibility = dialogState::toggleProviderTextSecretVisibility,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitProviderText(route, value.trim(), features)
                    dialogState.dismissProviderDialog()
                },
                enabled = validation.confirmEnabled,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = dialogState::dismissProviderDialog) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ProviderTextDialogContent(
    route: SettingsDialogRoute.RemoteProviderText,
    uiState: SettingsScreenUiState,
    form: FormState,
    validation: ProviderTextValidation,
    onValueChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        route.field.messageResId(uiState)?.let { messageResId ->
            Text(
                text = stringResource(messageResId),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            value = form.value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(route.field.labelResId())) },
            visualTransformation =
                if (route.secret && !form.secretVisible) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
            isError = validation.showError,
            supportingText =
                validation.errorResId?.let { errorResId ->
                    { Text(stringResource(errorResId)) }
                },
        )
        if (route.secret) {
            TextButton(onClick = onToggleVisibility) {
                Text(
                    stringResource(
                        if (form.secretVisible) {
                            R.string.settings_hide_password
                        } else {
                            R.string.settings_show_password
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ProviderConfirmationDialog(
    route: SettingsDialogRoute.RemoteProviderConfirmation,
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
) {
    when (route.action) {
        RemoteProviderConfirmationAction.GitResetRepository ->
            AlertDialog(
                onDismissRequest = dialogState::dismissProviderDialog,
                title = { Text(stringResource(R.string.settings_git_reset_repo_confirm_title)) },
                text = { Text(stringResource(R.string.settings_git_reset_repo_confirm_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            features.git.resetGitRepository()
                            dialogState.dismissProviderDialog()
                        },
                        enabled = !uiState.git.resetInProgress,
                    ) {
                        Text(stringResource(R.string.action_reset))
                    }
                },
                dismissButton = {
                    TextButton(onClick = dialogState::dismissProviderDialog) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
    }
}

@Composable
private fun GitConflictResolutionDialog(
    route: SettingsDialogRoute.RemoteProviderGitConflict,
    gitFeature: SettingsGitFeatureViewModel,
    dialogState: SettingsDialogState,
) {
    AlertDialog(
        onDismissRequest = dialogState::dismissProviderDialog,
        title = { Text(stringResource(R.string.settings_git_conflict_dialog_title)) },
        text = {
            Text(
                stringResource(
                    R.string.settings_git_conflict_dialog_message,
                    SettingsErrorPresenter.gitSyncErrorMessage(route.error.code, route.error.detail),
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    gitFeature.resolveGitConflictUsingLocal()
                    dialogState.dismissProviderDialog()
                },
            ) {
                Text(stringResource(R.string.settings_git_conflict_keep_local))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    gitFeature.resolveGitConflictUsingRemote()
                    dialogState.dismissProviderDialog()
                },
            ) {
                Text(stringResource(R.string.settings_git_conflict_use_remote))
            }
        },
    )
}

private data class ProviderTextValidation(
    val confirmEnabled: Boolean,
    val showError: Boolean = false,
    val errorResId: Int? = null,
)

private fun providerTextValidation(
    route: SettingsDialogRoute.RemoteProviderText,
    value: String,
    features: SettingsFeatures,
): ProviderTextValidation =
    when (route.field) {
        RemoteProviderTextField.GitRemoteUrl -> {
            val valid = features.git.isValidGitRemoteUrl(value)
            ProviderTextValidation(
                confirmEnabled = valid,
                showError = value.isNotBlank() && !valid,
                errorResId =
                    if (value.isNotBlank() && !valid) {
                        R.string.settings_git_remote_url_error_https_required
                    } else {
                        null
                    },
            )
        }
        RemoteProviderTextField.WebDavBaseUrl,
        RemoteProviderTextField.WebDavEndpointUrl,
        -> {
            val valid = features.webDav.isValidWebDavUrl(value)
            ProviderTextValidation(confirmEnabled = valid, showError = value.isNotBlank() && !valid)
        }
        RemoteProviderTextField.WebDavUsername,
        RemoteProviderTextField.WebDavPassword,
        -> ProviderTextValidation(confirmEnabled = value.isNotBlank())
        RemoteProviderTextField.S3EndpointUrl -> {
            val valid = value.isBlank() || features.s3.isValidEndpointUrl(value)
            ProviderTextValidation(
                confirmEnabled = valid,
                showError = value.isNotBlank() && !valid,
                errorResId =
                    if (value.isNotBlank() && !valid) {
                        R.string.settings_git_remote_url_error_https_required
                    } else {
                        null
                    },
            )
        }
        else -> ProviderTextValidation(confirmEnabled = true)
    }

private fun submitProviderText(
    route: SettingsDialogRoute.RemoteProviderText,
    value: String,
    features: SettingsFeatures,
) {
    when (route.field) {
        RemoteProviderTextField.GitRemoteUrl -> features.git.updateGitRemoteUrl(value)
        RemoteProviderTextField.GitPat -> features.git.updateGitPat(value)
        RemoteProviderTextField.GitAuthorName -> features.git.updateGitAuthorName(value)
        RemoteProviderTextField.GitAuthorEmail -> features.git.updateGitAuthorEmail(value)
        RemoteProviderTextField.WebDavBaseUrl -> features.webDav.updateBaseUrl(value)
        RemoteProviderTextField.WebDavEndpointUrl -> features.webDav.updateEndpointUrl(value)
        RemoteProviderTextField.WebDavUsername -> features.webDav.updateUsername(value)
        RemoteProviderTextField.WebDavPassword -> features.webDav.updatePassword(value)
        RemoteProviderTextField.S3EndpointUrl -> features.s3.updateEndpointUrl(value)
        RemoteProviderTextField.S3Region -> features.s3.updateRegion(value)
        RemoteProviderTextField.S3Bucket -> features.s3.updateBucket(value)
        RemoteProviderTextField.S3Prefix -> features.s3.updatePrefix(value)
        RemoteProviderTextField.S3AccessKeyId -> features.s3.updateAccessKeyId(value)
        RemoteProviderTextField.S3SecretAccessKey -> features.s3.updateSecretAccessKey(value)
        RemoteProviderTextField.S3SessionToken -> features.s3.updateSessionToken(value)
        RemoteProviderTextField.S3EncryptionPassword -> features.s3.updateEncryptionPassword(value)
        RemoteProviderTextField.S3EncryptionPassword2 -> features.s3.updateEncryptionPassword2(value)
        RemoteProviderTextField.S3RcloneEncryptedSuffix -> features.s3.updateRcloneEncryptedSuffix(value)
    }
}

private fun RemoteProviderTextField.titleResId(): Int =
    when (this) {
        RemoteProviderTextField.GitRemoteUrl -> R.string.settings_git_remote_url
        RemoteProviderTextField.GitPat -> R.string.settings_git_pat_dialog_title
        RemoteProviderTextField.GitAuthorName -> R.string.settings_git_author_name
        RemoteProviderTextField.GitAuthorEmail -> R.string.settings_git_author_email
        RemoteProviderTextField.WebDavBaseUrl -> R.string.settings_webdav_base_url
        RemoteProviderTextField.WebDavEndpointUrl -> R.string.settings_webdav_endpoint_url
        RemoteProviderTextField.WebDavUsername -> R.string.settings_webdav_username
        RemoteProviderTextField.WebDavPassword -> R.string.settings_webdav_password_dialog_title
        RemoteProviderTextField.S3EndpointUrl -> R.string.settings_s3_endpoint_url
        RemoteProviderTextField.S3Region -> R.string.settings_s3_region
        RemoteProviderTextField.S3Bucket -> R.string.settings_s3_bucket
        RemoteProviderTextField.S3Prefix -> R.string.settings_s3_prefix
        RemoteProviderTextField.S3AccessKeyId -> R.string.settings_s3_access_key_id
        RemoteProviderTextField.S3SecretAccessKey -> R.string.settings_s3_secret_access_key
        RemoteProviderTextField.S3SessionToken -> R.string.settings_s3_session_token
        RemoteProviderTextField.S3EncryptionPassword -> R.string.settings_s3_encryption_password
        RemoteProviderTextField.S3EncryptionPassword2 -> R.string.settings_s3_encryption_password2
        RemoteProviderTextField.S3RcloneEncryptedSuffix -> R.string.settings_s3_rclone_encrypted_suffix
    }

private fun RemoteProviderTextField.labelResId(): Int =
    when (this) {
        RemoteProviderTextField.GitRemoteUrl -> R.string.settings_git_remote_url_hint
        RemoteProviderTextField.GitPat -> R.string.settings_git_pat_hint
        RemoteProviderTextField.GitAuthorName -> R.string.settings_git_author_name_hint
        RemoteProviderTextField.GitAuthorEmail -> R.string.settings_git_author_email_hint
        RemoteProviderTextField.WebDavBaseUrl -> R.string.settings_webdav_base_url_hint
        RemoteProviderTextField.WebDavEndpointUrl -> R.string.settings_webdav_endpoint_url_hint
        RemoteProviderTextField.WebDavUsername -> R.string.settings_webdav_username_hint
        RemoteProviderTextField.WebDavPassword -> R.string.settings_webdav_password_hint
        RemoteProviderTextField.S3EndpointUrl -> R.string.settings_s3_endpoint_url_hint
        RemoteProviderTextField.S3Region -> R.string.settings_s3_region_hint
        RemoteProviderTextField.S3Bucket -> R.string.settings_s3_bucket_hint
        RemoteProviderTextField.S3Prefix -> R.string.settings_s3_prefix_hint
        RemoteProviderTextField.S3AccessKeyId -> R.string.settings_s3_access_key_id_hint
        RemoteProviderTextField.S3SecretAccessKey -> R.string.settings_s3_secret_access_key_hint
        RemoteProviderTextField.S3SessionToken -> R.string.settings_s3_session_token_hint
        RemoteProviderTextField.S3EncryptionPassword -> R.string.settings_s3_encryption_password_hint
        RemoteProviderTextField.S3EncryptionPassword2 -> R.string.settings_s3_encryption_password2_hint
        RemoteProviderTextField.S3RcloneEncryptedSuffix -> R.string.settings_s3_rclone_encrypted_suffix_hint
    }

private fun RemoteProviderTextField.messageResId(uiState: SettingsScreenUiState): Int? =
    when (this) {
        RemoteProviderTextField.GitPat -> R.string.settings_git_pat_dialog_message
        RemoteProviderTextField.WebDavPassword -> webDavPasswordMessageRes(uiState.webDav.provider)
        RemoteProviderTextField.S3EncryptionPassword -> R.string.settings_s3_encryption_password_message
        RemoteProviderTextField.S3EncryptionPassword2 -> R.string.settings_s3_encryption_password2_message
        RemoteProviderTextField.S3RcloneEncryptedSuffix -> R.string.settings_s3_rclone_encrypted_suffix_message
        else -> null
    }

private fun webDavPasswordMessageRes(provider: WebDavProvider): Int =
    when (provider) {
        WebDavProvider.NUTSTORE -> R.string.settings_webdav_password_dialog_message_nutstore
        WebDavProvider.NEXTCLOUD -> R.string.settings_webdav_password_dialog_message_nextcloud
        WebDavProvider.CUSTOM -> R.string.settings_webdav_password_dialog_message_custom
    }
