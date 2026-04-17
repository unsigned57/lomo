package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SwitchPreferenceItem

@Composable
fun S3SyncSettingsSection(
    state: S3SectionState,
    pathStyleLabel: String,
    encryptionModeLabel: String,
    rcloneFilenameEncryptionLabel: String,
    rcloneFilenameEncodingLabel: String,
    syncIntervalLabel: String,
    syncNowSubtitle: String,
    connectionSubtitle: String,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenEndpointUrlDialog: () -> Unit,
    onOpenRegionDialog: () -> Unit,
    onOpenBucketDialog: () -> Unit,
    onOpenPrefixDialog: () -> Unit,
    onSelectLocalSyncDirectory: () -> Unit,
    onClearLocalSyncDirectory: () -> Unit,
    onOpenAccessKeyIdDialog: () -> Unit,
    onOpenSecretAccessKeyDialog: () -> Unit,
    onOpenSessionTokenDialog: () -> Unit,
    onOpenPathStyleDialog: () -> Unit,
    onOpenEncryptionModeDialog: () -> Unit,
    onOpenEncryptionPasswordDialog: () -> Unit,
    onOpenEncryptionPassword2Dialog: () -> Unit,
    onOpenRcloneFilenameEncryptionDialog: () -> Unit,
    onOpenRcloneFilenameEncodingDialog: () -> Unit,
    onToggleRcloneDirectoryNameEncryption: (Boolean) -> Unit,
    onToggleRcloneDataEncryptionEnabled: (Boolean) -> Unit,
    onOpenRcloneEncryptedSuffixDialog: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onOpenSyncIntervalDialog: () -> Unit,
    onToggleSyncOnRefresh: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onTestConnection: () -> Unit,
) {
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_s3_sync_enable),
        subtitle = stringResource(R.string.settings_s3_sync_enable_subtitle),
        icon = Icons.Outlined.Sync,
        checked = state.enabled,
        onCheckedChange = onToggleEnabled,
    )
    SettingsExpandableContent(
        visible = state.enabled,
        label = "S3SyncAdvancedVisibility",
    ) {
        S3SyncAdvancedContent(
            state = state,
            pathStyleLabel = pathStyleLabel,
            encryptionModeLabel = encryptionModeLabel,
            rcloneFilenameEncryptionLabel = rcloneFilenameEncryptionLabel,
            rcloneFilenameEncodingLabel = rcloneFilenameEncodingLabel,
            syncIntervalLabel = syncIntervalLabel,
            syncNowSubtitle = syncNowSubtitle,
            connectionSubtitle = connectionSubtitle,
            onOpenEndpointUrlDialog = onOpenEndpointUrlDialog,
            onOpenRegionDialog = onOpenRegionDialog,
            onOpenBucketDialog = onOpenBucketDialog,
            onOpenPrefixDialog = onOpenPrefixDialog,
            onSelectLocalSyncDirectory = onSelectLocalSyncDirectory,
            onClearLocalSyncDirectory = onClearLocalSyncDirectory,
            onOpenAccessKeyIdDialog = onOpenAccessKeyIdDialog,
            onOpenSecretAccessKeyDialog = onOpenSecretAccessKeyDialog,
            onOpenSessionTokenDialog = onOpenSessionTokenDialog,
            onOpenPathStyleDialog = onOpenPathStyleDialog,
            onOpenEncryptionModeDialog = onOpenEncryptionModeDialog,
            onOpenEncryptionPasswordDialog = onOpenEncryptionPasswordDialog,
            onOpenEncryptionPassword2Dialog = onOpenEncryptionPassword2Dialog,
            onOpenRcloneFilenameEncryptionDialog = onOpenRcloneFilenameEncryptionDialog,
            onOpenRcloneFilenameEncodingDialog = onOpenRcloneFilenameEncodingDialog,
            onToggleRcloneDirectoryNameEncryption = onToggleRcloneDirectoryNameEncryption,
            onToggleRcloneDataEncryptionEnabled = onToggleRcloneDataEncryptionEnabled,
            onOpenRcloneEncryptedSuffixDialog = onOpenRcloneEncryptedSuffixDialog,
            onToggleAutoSync = onToggleAutoSync,
            onOpenSyncIntervalDialog = onOpenSyncIntervalDialog,
            onToggleSyncOnRefresh = onToggleSyncOnRefresh,
            onSyncNow = onSyncNow,
            onTestConnection = onTestConnection,
        )
    }
}

@Composable
private fun S3SyncAdvancedContent(
    state: S3SectionState,
    pathStyleLabel: String,
    encryptionModeLabel: String,
    rcloneFilenameEncryptionLabel: String,
    rcloneFilenameEncodingLabel: String,
    syncIntervalLabel: String,
    syncNowSubtitle: String,
    connectionSubtitle: String,
    onOpenEndpointUrlDialog: () -> Unit,
    onOpenRegionDialog: () -> Unit,
    onOpenBucketDialog: () -> Unit,
    onOpenPrefixDialog: () -> Unit,
    onSelectLocalSyncDirectory: () -> Unit,
    onClearLocalSyncDirectory: () -> Unit,
    onOpenAccessKeyIdDialog: () -> Unit,
    onOpenSecretAccessKeyDialog: () -> Unit,
    onOpenSessionTokenDialog: () -> Unit,
    onOpenPathStyleDialog: () -> Unit,
    onOpenEncryptionModeDialog: () -> Unit,
    onOpenEncryptionPasswordDialog: () -> Unit,
    onOpenEncryptionPassword2Dialog: () -> Unit,
    onOpenRcloneFilenameEncryptionDialog: () -> Unit,
    onOpenRcloneFilenameEncodingDialog: () -> Unit,
    onToggleRcloneDirectoryNameEncryption: (Boolean) -> Unit,
    onToggleRcloneDataEncryptionEnabled: (Boolean) -> Unit,
    onOpenRcloneEncryptedSuffixDialog: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onOpenSyncIntervalDialog: () -> Unit,
    onToggleSyncOnRefresh: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onTestConnection: () -> Unit,
) {
    Column {
        S3ConnectionPreferences(
            state = state,
            pathStyleLabel = pathStyleLabel,
            encryptionModeLabel = encryptionModeLabel,
            rcloneFilenameEncryptionLabel = rcloneFilenameEncryptionLabel,
            rcloneFilenameEncodingLabel = rcloneFilenameEncodingLabel,
            onOpenEndpointUrlDialog = onOpenEndpointUrlDialog,
            onOpenRegionDialog = onOpenRegionDialog,
            onOpenBucketDialog = onOpenBucketDialog,
            onOpenPrefixDialog = onOpenPrefixDialog,
            onSelectLocalSyncDirectory = onSelectLocalSyncDirectory,
            onClearLocalSyncDirectory = onClearLocalSyncDirectory,
            onOpenAccessKeyIdDialog = onOpenAccessKeyIdDialog,
            onOpenSecretAccessKeyDialog = onOpenSecretAccessKeyDialog,
            onOpenSessionTokenDialog = onOpenSessionTokenDialog,
            onOpenPathStyleDialog = onOpenPathStyleDialog,
            onOpenEncryptionModeDialog = onOpenEncryptionModeDialog,
            onOpenEncryptionPasswordDialog = onOpenEncryptionPasswordDialog,
            onOpenEncryptionPassword2Dialog = onOpenEncryptionPassword2Dialog,
            onOpenRcloneFilenameEncryptionDialog = onOpenRcloneFilenameEncryptionDialog,
            onOpenRcloneFilenameEncodingDialog = onOpenRcloneFilenameEncodingDialog,
            onToggleRcloneDirectoryNameEncryption = onToggleRcloneDirectoryNameEncryption,
            onToggleRcloneDataEncryptionEnabled = onToggleRcloneDataEncryptionEnabled,
            onOpenRcloneEncryptedSuffixDialog = onOpenRcloneEncryptedSuffixDialog,
        )
        S3BehaviorPreferences(
            state = state,
            syncIntervalLabel = syncIntervalLabel,
            onToggleAutoSync = onToggleAutoSync,
            onOpenSyncIntervalDialog = onOpenSyncIntervalDialog,
            onToggleSyncOnRefresh = onToggleSyncOnRefresh,
        )
        S3ActionPreferences(
            syncNowSubtitle = syncNowSubtitle,
            connectionSubtitle = connectionSubtitle,
            onSyncNow = onSyncNow,
            onTestConnection = onTestConnection,
        )
    }
}

@Composable
private fun S3ConnectionPreferences(
    state: S3SectionState,
    pathStyleLabel: String,
    encryptionModeLabel: String,
    rcloneFilenameEncryptionLabel: String,
    rcloneFilenameEncodingLabel: String,
    onOpenEndpointUrlDialog: () -> Unit,
    onOpenRegionDialog: () -> Unit,
    onOpenBucketDialog: () -> Unit,
    onOpenPrefixDialog: () -> Unit,
    onSelectLocalSyncDirectory: () -> Unit,
    onClearLocalSyncDirectory: () -> Unit,
    onOpenAccessKeyIdDialog: () -> Unit,
    onOpenSecretAccessKeyDialog: () -> Unit,
    onOpenSessionTokenDialog: () -> Unit,
    onOpenPathStyleDialog: () -> Unit,
    onOpenEncryptionModeDialog: () -> Unit,
    onOpenEncryptionPasswordDialog: () -> Unit,
    onOpenEncryptionPassword2Dialog: () -> Unit,
    onOpenRcloneFilenameEncryptionDialog: () -> Unit,
    onOpenRcloneFilenameEncodingDialog: () -> Unit,
    onToggleRcloneDirectoryNameEncryption: (Boolean) -> Unit,
    onToggleRcloneDataEncryptionEnabled: (Boolean) -> Unit,
    onOpenRcloneEncryptedSuffixDialog: () -> Unit,
) {
    Column {
        S3EndpointPreferences(
            state = state,
            onOpenEndpointUrlDialog = onOpenEndpointUrlDialog,
            onOpenRegionDialog = onOpenRegionDialog,
            onOpenBucketDialog = onOpenBucketDialog,
            onOpenPrefixDialog = onOpenPrefixDialog,
            onSelectLocalSyncDirectory = onSelectLocalSyncDirectory,
            onClearLocalSyncDirectory = onClearLocalSyncDirectory,
        )
        S3CredentialPreferences(
            state = state,
            onOpenAccessKeyIdDialog = onOpenAccessKeyIdDialog,
            onOpenSecretAccessKeyDialog = onOpenSecretAccessKeyDialog,
            onOpenSessionTokenDialog = onOpenSessionTokenDialog,
        )
        S3EncryptionPreferences(
            state = state,
            pathStyleLabel = pathStyleLabel,
            encryptionModeLabel = encryptionModeLabel,
            rcloneFilenameEncryptionLabel = rcloneFilenameEncryptionLabel,
            rcloneFilenameEncodingLabel = rcloneFilenameEncodingLabel,
            onOpenPathStyleDialog = onOpenPathStyleDialog,
            onOpenEncryptionModeDialog = onOpenEncryptionModeDialog,
            onOpenEncryptionPasswordDialog = onOpenEncryptionPasswordDialog,
            onOpenEncryptionPassword2Dialog = onOpenEncryptionPassword2Dialog,
            onOpenRcloneFilenameEncryptionDialog = onOpenRcloneFilenameEncryptionDialog,
            onOpenRcloneFilenameEncodingDialog = onOpenRcloneFilenameEncodingDialog,
            onToggleRcloneDirectoryNameEncryption = onToggleRcloneDirectoryNameEncryption,
            onToggleRcloneDataEncryptionEnabled = onToggleRcloneDataEncryptionEnabled,
            onOpenRcloneEncryptedSuffixDialog = onOpenRcloneEncryptedSuffixDialog,
        )
    }
}

@Composable
private fun S3EndpointPreferences(
    state: S3SectionState,
    onOpenEndpointUrlDialog: () -> Unit,
    onOpenRegionDialog: () -> Unit,
    onOpenBucketDialog: () -> Unit,
    onOpenPrefixDialog: () -> Unit,
    onSelectLocalSyncDirectory: () -> Unit,
    onClearLocalSyncDirectory: () -> Unit,
) {
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_endpoint_url),
        subtitle = state.endpointUrl.ifBlank { stringResource(R.string.settings_not_set) },
        icon = Icons.Outlined.Link,
        onClick = onOpenEndpointUrlDialog,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_region),
        subtitle = state.region.ifBlank { stringResource(R.string.settings_not_set) },
        icon = Icons.Outlined.AccessTime,
        onClick = onOpenRegionDialog,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_bucket),
        subtitle = state.bucket.ifBlank { stringResource(R.string.settings_not_set) },
        icon = Icons.Default.Folder,
        onClick = onOpenBucketDialog,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_prefix),
        subtitle = state.prefix.ifBlank { stringResource(R.string.settings_s3_prefix_root) },
        icon = Icons.Default.Folder,
        onClick = onOpenPrefixDialog,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_local_sync_directory),
        subtitle = s3LocalSyncDirectorySubtitle(state.localSyncDirectory),
        icon = Icons.Default.Folder,
        onClick = onSelectLocalSyncDirectory,
    )
    if (state.localSyncDirectory.isNotBlank()) {
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_s3_local_sync_directory_default),
            subtitle = stringResource(R.string.settings_s3_local_sync_directory_default_subtitle),
            icon = Icons.Outlined.Refresh,
            onClick = onClearLocalSyncDirectory,
        )
    }
}

@Composable
private fun S3CredentialPreferences(
    state: S3SectionState,
    onOpenAccessKeyIdDialog: () -> Unit,
    onOpenSecretAccessKeyDialog: () -> Unit,
    onOpenSessionTokenDialog: () -> Unit,
) {
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_access_key_id),
        subtitle = s3CredentialSubtitle(state.accessKeyConfigured),
        icon = Icons.Default.Lock,
        onClick = onOpenAccessKeyIdDialog,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_secret_access_key),
        subtitle = s3CredentialSubtitle(state.secretAccessKeyConfigured),
        icon = Icons.Default.Lock,
        onClick = onOpenSecretAccessKeyDialog,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_session_token),
        subtitle =
            stringResource(
                if (state.sessionTokenConfigured) {
                    R.string.settings_s3_session_token_configured
                } else {
                    R.string.settings_s3_session_token_not_set
                },
            ),
        icon = Icons.Default.Lock,
        onClick = onOpenSessionTokenDialog,
    )
}

@Composable
private fun S3EncryptionPreferences(
    state: S3SectionState,
    pathStyleLabel: String,
    encryptionModeLabel: String,
    rcloneFilenameEncryptionLabel: String,
    rcloneFilenameEncodingLabel: String,
    onOpenPathStyleDialog: () -> Unit,
    onOpenEncryptionModeDialog: () -> Unit,
    onOpenEncryptionPasswordDialog: () -> Unit,
    onOpenEncryptionPassword2Dialog: () -> Unit,
    onOpenRcloneFilenameEncryptionDialog: () -> Unit,
    onOpenRcloneFilenameEncodingDialog: () -> Unit,
    onToggleRcloneDirectoryNameEncryption: (Boolean) -> Unit,
    onToggleRcloneDataEncryptionEnabled: (Boolean) -> Unit,
    onOpenRcloneEncryptedSuffixDialog: () -> Unit,
) {
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_path_style),
        subtitle = pathStyleLabel,
        icon = Icons.Outlined.Link,
        onClick = onOpenPathStyleDialog,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_encryption_mode),
        subtitle = encryptionModeLabel,
        icon = Icons.Default.Lock,
        onClick = onOpenEncryptionModeDialog,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_encryption_password),
        subtitle =
            stringResource(
                if (state.encryptionPasswordConfigured) {
                    R.string.settings_s3_encryption_password_configured
                } else {
                    R.string.settings_s3_encryption_password_not_set
                },
            ),
        icon = Icons.Default.Lock,
        enabled = state.encryptionMode != S3EncryptionMode.NONE,
        showChevron = state.encryptionMode != S3EncryptionMode.NONE,
        onClick = onOpenEncryptionPasswordDialog,
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
                    stringResource(
                        if (state.encryptionPassword2Configured) {
                            R.string.settings_s3_encryption_password2_configured
                        } else {
                            R.string.settings_s3_encryption_password2_not_set
                        },
                    ),
                icon = Icons.Default.Lock,
                onClick = onOpenEncryptionPassword2Dialog,
            )
            SettingsDivider()
            PreferenceItem(
                title = stringResource(R.string.settings_s3_rclone_filename_encryption),
                subtitle = rcloneFilenameEncryptionLabel,
                icon = Icons.Outlined.DataObject,
                onClick = onOpenRcloneFilenameEncryptionDialog,
            )
            when (state.rcloneFilenameEncryption) {
                S3RcloneFilenameEncryption.STANDARD -> {
                    SettingsDivider()
                    PreferenceItem(
                        title = stringResource(R.string.settings_s3_rclone_filename_encoding),
                        subtitle = rcloneFilenameEncodingLabel,
                        icon = Icons.Outlined.DataObject,
                        onClick = onOpenRcloneFilenameEncodingDialog,
                    )
                    SettingsDivider()
                    SwitchPreferenceItem(
                        title = stringResource(R.string.settings_s3_rclone_directory_name_encryption),
                        subtitle = stringResource(R.string.settings_s3_rclone_directory_name_encryption_subtitle),
                        icon = Icons.Default.Lock,
                        checked = state.rcloneDirectoryNameEncryption,
                        onCheckedChange = onToggleRcloneDirectoryNameEncryption,
                    )
                }

                S3RcloneFilenameEncryption.OFF -> {
                    SettingsDivider()
                    PreferenceItem(
                        title = stringResource(R.string.settings_s3_rclone_encrypted_suffix),
                        subtitle = s3EncryptedSuffixSubtitle(state.rcloneEncryptedSuffix),
                        icon = Icons.Outlined.DataObject,
                        onClick = onOpenRcloneEncryptedSuffixDialog,
                    )
                }

                S3RcloneFilenameEncryption.OBFUSCATE -> Unit
            }
            SettingsDivider()
            SwitchPreferenceItem(
                title = stringResource(R.string.settings_s3_rclone_data_encryption),
                subtitle = stringResource(R.string.settings_s3_rclone_data_encryption_subtitle),
                icon = Icons.Default.Lock,
                checked = state.rcloneDataEncryptionEnabled,
                onCheckedChange = onToggleRcloneDataEncryptionEnabled,
            )
        }
    }
}

@Composable
private fun S3BehaviorPreferences(
    state: S3SectionState,
    syncIntervalLabel: String,
    onToggleAutoSync: (Boolean) -> Unit,
    onOpenSyncIntervalDialog: () -> Unit,
    onToggleSyncOnRefresh: (Boolean) -> Unit,
) {
    SettingsDivider()
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_s3_auto_sync),
        subtitle = stringResource(R.string.settings_s3_auto_sync_subtitle),
        icon = Icons.Outlined.Schedule,
        checked = state.autoSyncEnabled,
        onCheckedChange = onToggleAutoSync,
    )
    SettingsExpandableContent(
        visible = state.autoSyncEnabled,
        label = "S3AutoSyncIntervalVisibility",
    ) {
        Column {
            SettingsDivider()
            PreferenceItem(
                title = stringResource(R.string.settings_s3_sync_interval),
                subtitle = syncIntervalLabel,
                icon = Icons.Outlined.Schedule,
                onClick = onOpenSyncIntervalDialog,
            )
        }
    }
    SettingsDivider()
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_s3_sync_on_refresh),
        subtitle = stringResource(R.string.settings_s3_sync_on_refresh_subtitle),
        icon = Icons.Outlined.Refresh,
        checked = state.syncOnRefreshEnabled,
        onCheckedChange = onToggleSyncOnRefresh,
    )
}

@Composable
private fun S3ActionPreferences(
    syncNowSubtitle: String,
    connectionSubtitle: String,
    onSyncNow: () -> Unit,
    onTestConnection: () -> Unit,
) {
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_sync_now),
        subtitle = syncNowSubtitle,
        icon = Icons.Outlined.Sync,
        onClick = onSyncNow,
    )
    SettingsDivider()
    PreferenceItem(
        title = stringResource(R.string.settings_s3_test_connection),
        subtitle = connectionSubtitle,
        icon = Icons.Outlined.Link,
        onClick = onTestConnection,
    )
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
