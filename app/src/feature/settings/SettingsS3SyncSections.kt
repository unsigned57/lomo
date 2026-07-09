package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SwitchPreferenceItem

data class S3SyncSectionLabels(
    val common: RemoteProviderSectionLabels,
    val pathStyle: String,
    val encryptionMode: String,
    val rcloneFilenameEncryption: String,
    val rcloneFilenameEncoding: String,
)

data class S3SyncDialogActions(
    val endpoint: S3EndpointDialogActions,
    val credentials: S3CredentialDialogActions,
    val encryption: S3EncryptionDialogActions,
)

data class S3EndpointDialogActions(
    val openEndpointUrl: () -> Unit,
    val openRegion: () -> Unit,
    val openBucket: () -> Unit,
    val openPrefix: () -> Unit,
    val selectLocalSyncDirectory: () -> Unit,
    val clearLocalSyncDirectory: () -> Unit,
)

data class S3CredentialDialogActions(
    val openAccessKeyId: () -> Unit,
    val openSecretAccessKey: () -> Unit,
    val openSessionToken: () -> Unit,
)

data class S3EncryptionDialogActions(
    val openPathStyle: () -> Unit,
    val openEncryptionMode: () -> Unit,
    val openEncryptionPassword: () -> Unit,
    val openEncryptionPassword2: () -> Unit,
    val openRcloneFilenameEncryption: () -> Unit,
    val openRcloneFilenameEncoding: () -> Unit,
    val openRcloneEncryptedSuffix: () -> Unit,
)

data class S3SyncSpecificActions(
    val toggleRcloneDirectoryNameEncryption: (Boolean) -> Unit,
    val toggleRcloneDataEncryptionEnabled: (Boolean) -> Unit,
)

@Composable
fun S3SyncSettingsSection(
    state: S3SectionState,
    labels: S3SyncSectionLabels,
    dialogs: S3SyncDialogActions,
    specificActions: S3SyncSpecificActions,
    actions: RemoteProviderSectionActions,
    modifier: Modifier = Modifier,
) {
    RemoteProviderSectionSurface(
        providerSettings = state.providerSettings,
        labels = labels.common,
        actions = actions,
        modifier = modifier,
        providerSettingsContent = {
            S3ConnectionPreferences(
                state = state,
                labels = labels,
                dialogs = dialogs,
                specificActions = specificActions,
            )
        },
        providerActionContent = null,
    )
}

@Composable
private fun S3ConnectionPreferences(
    state: S3SectionState,
    labels: S3SyncSectionLabels,
    dialogs: S3SyncDialogActions,
    specificActions: S3SyncSpecificActions,
) {
    Column {
        S3EndpointPreferences(
            state = state,
            actions = dialogs.endpoint,
        )
        S3CredentialPreferences(
            state = state,
            actions = dialogs.credentials,
        )
        S3EncryptionPreferences(
            state = state,
            labels = labels,
            dialogs = dialogs.encryption,
            actions = specificActions,
        )
    }
}

@Composable
private fun S3EndpointPreferences(
    state: S3SectionState,
    actions: S3EndpointDialogActions,
) {
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_endpoint_url),
        subtitle = state.endpointUrl.ifBlank { stringResource(R.string.settings_not_set) },
        icon = Icons.Outlined.Link,
        onClick = actions.openEndpointUrl,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_region),
        subtitle = state.region.ifBlank { stringResource(R.string.settings_not_set) },
        icon = Icons.Outlined.AccessTime,
        onClick = actions.openRegion,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_bucket),
        subtitle = state.bucket.ifBlank { stringResource(R.string.settings_not_set) },
        icon = Icons.Outlined.FolderOpen,
        onClick = actions.openBucket,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_prefix),
        subtitle = state.prefix.ifBlank { stringResource(R.string.settings_s3_prefix_root) },
        icon = Icons.Outlined.FolderOpen,
        onClick = actions.openPrefix,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_local_sync_directory),
        subtitle = s3LocalSyncDirectorySubtitle(state.localSyncDirectory),
        icon = Icons.Outlined.FolderOpen,
        onClick = actions.selectLocalSyncDirectory,
    )
    if (state.localSyncDirectory.isNotBlank()) {
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_s3_local_sync_directory_default),
            subtitle = stringResource(R.string.settings_s3_local_sync_directory_default_subtitle),
            icon = Icons.Outlined.Refresh,
            onClick = actions.clearLocalSyncDirectory,
        )
    }
}

@Composable
private fun S3CredentialPreferences(
    state: S3SectionState,
    actions: S3CredentialDialogActions,
) {
    val providerSettings = state.providerSettings
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_access_key_id),
        subtitle =
            credentialStatusSubtitle(
                status = providerSettings.credentialStatus(RemoteProviderCredentialField.S3AccessKeyId),
                configuredResId = R.string.settings_s3_access_key_configured,
                missingResId = R.string.settings_s3_access_key_not_set,
            ),
        icon = Icons.Outlined.Lock,
        onClick = actions.openAccessKeyId,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_secret_access_key),
        subtitle =
            credentialStatusSubtitle(
                status = providerSettings.credentialStatus(RemoteProviderCredentialField.S3SecretAccessKey),
                configuredResId = R.string.settings_s3_access_key_configured,
                missingResId = R.string.settings_s3_access_key_not_set,
            ),
        icon = Icons.Outlined.Lock,
        onClick = actions.openSecretAccessKey,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_session_token),
        subtitle =
            credentialStatusSubtitle(
                status = providerSettings.credentialStatus(RemoteProviderCredentialField.S3SessionToken),
                configuredResId = R.string.settings_s3_session_token_configured,
                missingResId = R.string.settings_s3_session_token_not_set,
            ),
        icon = Icons.Outlined.Lock,
        onClick = actions.openSessionToken,
    )
}

@Composable
private fun S3EncryptionPreferences(
    state: S3SectionState,
    labels: S3SyncSectionLabels,
    dialogs: S3EncryptionDialogActions,
    actions: S3SyncSpecificActions,
) {
    val providerSettings = state.providerSettings
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_path_style),
        subtitle = labels.pathStyle,
        icon = Icons.Outlined.Link,
        onClick = dialogs.openPathStyle,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_encryption_mode),
        subtitle = labels.encryptionMode,
        icon = Icons.Outlined.Lock,
        onClick = dialogs.openEncryptionMode,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_encryption_password),
        subtitle =
            credentialStatusSubtitle(
                status = providerSettings.credentialStatus(RemoteProviderCredentialField.S3EncryptionPassword),
                configuredResId = R.string.settings_s3_encryption_password_configured,
                missingResId = R.string.settings_s3_encryption_password_not_set,
            ),
        icon = Icons.Outlined.Lock,
        enabled = state.encryptionMode != S3EncryptionMode.NONE,
        showChevron = state.encryptionMode != S3EncryptionMode.NONE,
        onClick = dialogs.openEncryptionPassword,
    )
    SettingsExpandableContent(
        visible = state.encryptionMode == S3EncryptionMode.RCLONE_CRYPT,
        label = "S3RcloneCryptAdvancedVisibility",
    ) {
        Column {
            SettingsDivider()
            PreferenceItem(
                title = stringResource(R.string.settings_s3_encryption_password2),
                subtitle =
                    credentialStatusSubtitle(
                        status = providerSettings.credentialStatus(
                            RemoteProviderCredentialField.S3EncryptionPassword2,
                        ),
                        configuredResId = R.string.settings_s3_encryption_password2_configured,
                        missingResId = R.string.settings_s3_encryption_password2_not_set,
                    ),
                icon = Icons.Outlined.Lock,
                onClick = dialogs.openEncryptionPassword2,
            )
            SettingsDivider()
            PreferenceItem(
                title = stringResource(R.string.settings_s3_rclone_filename_encryption),
                subtitle = labels.rcloneFilenameEncryption,
                icon = Icons.Outlined.DataObject,
                onClick = dialogs.openRcloneFilenameEncryption,
            )
            when (state.rcloneFilenameEncryption) {
                S3RcloneFilenameEncryption.STANDARD -> {
                    SettingsDivider()
                    PreferenceItem(
                        title = stringResource(R.string.settings_s3_rclone_filename_encoding),
                        subtitle = labels.rcloneFilenameEncoding,
                        icon = Icons.Outlined.DataObject,
                        onClick = dialogs.openRcloneFilenameEncoding,
                    )
                    SettingsDivider()
                    SwitchPreferenceItem(
                        title = stringResource(R.string.settings_s3_rclone_directory_name_encryption),
                        subtitle = stringResource(R.string.settings_s3_rclone_directory_name_encryption_subtitle),
                        icon = Icons.Outlined.Lock,
                        checked = state.rcloneDirectoryNameEncryption,
                        onCheckedChange = actions.toggleRcloneDirectoryNameEncryption,
                    )
                }

                S3RcloneFilenameEncryption.OFF -> {
                    SettingsDivider()
                    PreferenceItem(
                        title = stringResource(R.string.settings_s3_rclone_encrypted_suffix),
                        subtitle = s3EncryptedSuffixSubtitle(state.rcloneEncryptedSuffix),
                        icon = Icons.Outlined.DataObject,
                        onClick = dialogs.openRcloneEncryptedSuffix,
                    )
                }

                S3RcloneFilenameEncryption.OBFUSCATE -> Unit
            }
            SettingsDivider()
            SwitchPreferenceItem(
                title = stringResource(R.string.settings_s3_rclone_data_encryption),
                subtitle = stringResource(R.string.settings_s3_rclone_data_encryption_subtitle),
                icon = Icons.Outlined.Lock,
                checked = state.rcloneDataEncryptionEnabled,
                onCheckedChange = actions.toggleRcloneDataEncryptionEnabled,
            )
        }
    }
}

@Composable
private fun s3EncryptedSuffixSubtitle(rawValue: String): String {
    val normalized = rawValue.trim()
    return if (normalized.isBlank() || normalized.equals("none", ignoreCase = true)) {
        stringResource(R.string.settings_s3_rclone_encrypted_suffix_none)
    } else {
        normalized
    }
}
