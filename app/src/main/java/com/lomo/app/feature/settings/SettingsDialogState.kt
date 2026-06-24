package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lomo.domain.model.SyncBackendType

data class FormState(
    val initialValue: String = "",
    val value: String,
    val secretVisible: Boolean = false,
) {
    val isDirty: Boolean
        get() = value != initialValue
}

sealed interface SettingsDialogRoute {
    data class RemoteProviderText(
        val field: RemoteProviderTextField,
    ) : SettingsDialogRoute {
        val provider: SyncBackendType = field.provider
        val secret: Boolean = field.secret
    }

    data class RemoteProviderSelection(
        val field: RemoteProviderSelectionField,
    ) : SettingsDialogRoute {
        val provider: SyncBackendType = field.provider
    }

    data class RemoteProviderConfirmation(
        val action: RemoteProviderConfirmationAction,
    ) : SettingsDialogRoute {
        val provider: SyncBackendType = action.provider
    }

    data class RemoteProviderGitConflict(
        val error: SettingsOperationError.GitSync,
    ) : SettingsDialogRoute
}

enum class RemoteProviderTextField {
    GitRemoteUrl,
    GitPat,
    GitAuthorName,
    GitAuthorEmail,
    WebDavBaseUrl,
    WebDavEndpointUrl,
    WebDavUsername,
    WebDavPassword,
    S3EndpointUrl,
    S3Region,
    S3Bucket,
    S3Prefix,
    S3AccessKeyId,
    S3SecretAccessKey,
    S3SessionToken,
    S3EncryptionPassword,
    S3EncryptionPassword2,
    S3RcloneEncryptedSuffix,
}

enum class RemoteProviderCredentialField {
    GitPat,
    WebDavPassword,
    S3AccessKeyId,
    S3SecretAccessKey,
    S3SessionToken,
    S3EncryptionPassword,
    S3EncryptionPassword2,
}

enum class RemoteProviderSelectionField {
    GitSyncInterval,
    WebDavProvider,
    WebDavSyncInterval,
    S3SyncInterval,
    S3PathStyle,
    S3EncryptionMode,
    S3RcloneFilenameEncryption,
    S3RcloneFilenameEncoding,
}

enum class RemoteProviderConfirmationAction {
    GitResetRepository,
}

@Stable
class SettingsDialogState {
    var activeProviderDialogRoute by mutableStateOf<SettingsDialogRoute?>(null)
    var providerTextFormState by mutableStateOf(FormState(value = ""))

    var showDateDialog by mutableStateOf(false)
    var showTimeDialog by mutableStateOf(false)
    var showThemeDialog by mutableStateOf(false)
    var showFilenameDialog by mutableStateOf(false)
    var showTimestampDialog by mutableStateOf(false)
    var showLanguageDialog by mutableStateOf(false)
    var activeSubPage by mutableStateOf(SettingsSubPage.NONE)
    var showShareCardSignatureDialog by mutableStateOf(false)
    var shareCardSignatureInput by mutableStateOf("")
    var showMemoSnapshotCountDialog by mutableStateOf(false)
    var showMemoSnapshotAgeDialog by mutableStateOf(false)
    var pendingSnapshotDisableTarget by mutableStateOf<SettingsSnapshotDisableTarget?>(null)

    var showMigrationExportSettingsPasswordDialog by mutableStateOf(false)
    var showMigrationImportSettingsPasswordDialog by mutableStateOf(false)
    var migrationPasswordInput by mutableStateOf("")

    var showLanPairingDialog by mutableStateOf(false)
    var lanPairingCodeInput by mutableStateOf("")
    var lanPairingCodeVisible by mutableStateOf(false)
    var showDeviceNameDialog by mutableStateOf(false)
    var deviceNameInput by mutableStateOf("")

    var showGitResetConfirmDialog by mutableStateOf(false)
    var showGitConflictResolutionDialog by mutableStateOf(false)
    var gitConflictError by mutableStateOf<SettingsOperationError.GitSync?>(null)

    fun openProviderTextDialog(
        field: RemoteProviderTextField,
        initialValue: String,
    ) {
        activeProviderDialogRoute = SettingsDialogRoute.RemoteProviderText(field)
        providerTextFormState = FormState(initialValue = initialValue, value = initialValue)
    }

    fun openProviderSelectionDialog(field: RemoteProviderSelectionField) {
        activeProviderDialogRoute = SettingsDialogRoute.RemoteProviderSelection(field)
        providerTextFormState = FormState(value = "")
    }

    fun openProviderConfirmationDialog(action: RemoteProviderConfirmationAction) {
        activeProviderDialogRoute = SettingsDialogRoute.RemoteProviderConfirmation(action)
        providerTextFormState = FormState(value = "")
    }

    fun openProviderGitConflictDialog(error: SettingsOperationError.GitSync) {
        activeProviderDialogRoute = SettingsDialogRoute.RemoteProviderGitConflict(error)
        providerTextFormState = FormState(value = "")
    }

    fun updateProviderTextValue(value: String) {
        providerTextFormState = providerTextFormState.copy(value = value)
    }

    fun toggleProviderTextSecretVisibility() {
        providerTextFormState = providerTextFormState.copy(
            secretVisible = !providerTextFormState.secretVisible,
        )
    }

    fun dismissProviderDialog() {
        activeProviderDialogRoute = null
        providerTextFormState = FormState(value = "")
    }
}

internal fun SettingsDialogState.providerTextDialogAction(
    field: RemoteProviderTextField,
    initialValue: () -> String = { "" },
): () -> Unit = { openProviderTextDialog(field = field, initialValue = initialValue()) }

internal fun SettingsDialogState.providerSelectionDialogAction(
    field: RemoteProviderSelectionField,
): () -> Unit = { openProviderSelectionDialog(field) }

internal fun SettingsDialogState.providerConfirmationDialogAction(
    action: RemoteProviderConfirmationAction,
): () -> Unit = { openProviderConfirmationDialog(action) }

private val RemoteProviderTextField.provider: SyncBackendType
    get() =
        when (this) {
            RemoteProviderTextField.GitRemoteUrl,
            RemoteProviderTextField.GitPat,
            RemoteProviderTextField.GitAuthorName,
            RemoteProviderTextField.GitAuthorEmail,
            -> SyncBackendType.GIT
            RemoteProviderTextField.WebDavBaseUrl,
            RemoteProviderTextField.WebDavEndpointUrl,
            RemoteProviderTextField.WebDavUsername,
            RemoteProviderTextField.WebDavPassword,
            -> SyncBackendType.WEBDAV
            RemoteProviderTextField.S3EndpointUrl,
            RemoteProviderTextField.S3Region,
            RemoteProviderTextField.S3Bucket,
            RemoteProviderTextField.S3Prefix,
            RemoteProviderTextField.S3AccessKeyId,
            RemoteProviderTextField.S3SecretAccessKey,
            RemoteProviderTextField.S3SessionToken,
            RemoteProviderTextField.S3EncryptionPassword,
            RemoteProviderTextField.S3EncryptionPassword2,
            RemoteProviderTextField.S3RcloneEncryptedSuffix,
            -> SyncBackendType.S3
        }

private val RemoteProviderTextField.secret: Boolean
    get() =
        when (this) {
            RemoteProviderTextField.GitPat,
            RemoteProviderTextField.WebDavPassword,
            RemoteProviderTextField.S3SecretAccessKey,
            RemoteProviderTextField.S3SessionToken,
            RemoteProviderTextField.S3EncryptionPassword,
            RemoteProviderTextField.S3EncryptionPassword2,
            -> true
            RemoteProviderTextField.GitRemoteUrl,
            RemoteProviderTextField.GitAuthorName,
            RemoteProviderTextField.GitAuthorEmail,
            RemoteProviderTextField.WebDavBaseUrl,
            RemoteProviderTextField.WebDavEndpointUrl,
            RemoteProviderTextField.WebDavUsername,
            RemoteProviderTextField.S3EndpointUrl,
            RemoteProviderTextField.S3Region,
            RemoteProviderTextField.S3Bucket,
            RemoteProviderTextField.S3Prefix,
            RemoteProviderTextField.S3AccessKeyId,
            RemoteProviderTextField.S3RcloneEncryptedSuffix,
            -> false
        }

private val RemoteProviderSelectionField.provider: SyncBackendType
    get() =
        when (this) {
            RemoteProviderSelectionField.GitSyncInterval -> SyncBackendType.GIT
            RemoteProviderSelectionField.WebDavProvider,
            RemoteProviderSelectionField.WebDavSyncInterval,
            -> SyncBackendType.WEBDAV
            RemoteProviderSelectionField.S3SyncInterval,
            RemoteProviderSelectionField.S3PathStyle,
            RemoteProviderSelectionField.S3EncryptionMode,
            RemoteProviderSelectionField.S3RcloneFilenameEncryption,
            RemoteProviderSelectionField.S3RcloneFilenameEncoding,
            -> SyncBackendType.S3
        }

private val RemoteProviderConfirmationAction.provider: SyncBackendType
    get() =
        when (this) {
            RemoteProviderConfirmationAction.GitResetRepository -> SyncBackendType.GIT
        }

@Composable
fun rememberSettingsDialogState(): SettingsDialogState = remember { SettingsDialogState() }

enum class SettingsSnapshotDisableTarget {
    MEMO,
}

enum class SettingsSubPage {
    NONE,
    SYNC_BACKUP,
    STORAGE_FORMATS,
    INTERACTION_SECURITY,
    TYPOGRAPHY,
    COLOR_PALETTE,
    FONT,
    ABOUT
}
