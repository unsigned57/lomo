package com.lomo.app.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncState
import kotlinx.coroutines.launch

@Composable
internal fun rememberStoragePickerActions(
    storageFeature: SettingsStorageFeatureViewModel,
    s3Feature: SettingsS3FeatureViewModel,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
): StoragePickerActions =
    StoragePickerActions(
        openRoot =
            rememberTreePickerAction(
                onUriSelected = storageFeature::updateRootUri,
                snackbarHostState = snackbarHostState,
                unknownErrorMessage = unknownErrorMessage,
            ),
        openImage =
            rememberTreePickerAction(
                onUriSelected = storageFeature::updateImageUri,
                snackbarHostState = snackbarHostState,
                unknownErrorMessage = unknownErrorMessage,
            ),
        openVoice =
            rememberTreePickerAction(
                onUriSelected = storageFeature::updateVoiceUri,
                snackbarHostState = snackbarHostState,
                unknownErrorMessage = unknownErrorMessage,
            ),
        openSyncInbox =
            rememberTreePickerAction(
                onUriSelected = storageFeature::updateSyncInboxUri,
                snackbarHostState = snackbarHostState,
                unknownErrorMessage = unknownErrorMessage,
            ),
        openS3LocalSyncDirectory =
            rememberTreePickerAction(
                onUriSelected = s3Feature.updateLocalSyncDirectory,
                snackbarHostState = snackbarHostState,
                unknownErrorMessage = unknownErrorMessage,
            ),
    )

@Composable
private fun rememberTreePickerAction(
    onUriSelected: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            runCatching {
                persistTreePermission(context, uri)
                onUriSelected(uri.toString())
            }.onFailure { throwable ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        throwable.message?.takeIf { it.isNotBlank() } ?: unknownErrorMessage,
                    )
                }
            }
        }
    return { launcher.launch(null) }
}

private fun persistTreePermission(
    context: Context,
    uri: Uri,
) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    context.contentResolver.takePersistableUriPermission(uri, flags)
}

@Composable
internal fun HandleSettingsOperationError(
    operationError: SettingsOperationError?,
    gitFeature: SettingsGitFeatureViewModel,
    dialogState: SettingsDialogState,
    snackbarHostState: SnackbarHostState,
    messages: SettingsMessages,
    onClearOperationError: () -> Unit,
) {
    val localizedMessage =
        when (val error = operationError) {
            is SettingsOperationError.GitSync ->
                SettingsErrorPresenter.gitSyncErrorMessage(error.code, error.detail)
            is SettingsOperationError.Message -> error.text
            is SettingsOperationError.WebDavSync ->
                SettingsErrorPresenter.webDavSyncErrorMessage(error.code, error.detail)
            null -> null
        }
    LaunchedEffect(operationError) {
        val error = operationError ?: return@LaunchedEffect
        if (error is SettingsOperationError.GitSync && gitFeature.shouldShowGitConflictDialog(error.code)) {
            dialogState.gitConflictError = error
            dialogState.showGitConflictResolutionDialog = true
        } else {
            snackbarHostState.showSnackbar(localizedMessage ?: messages.unknownErrorMessage)
        }
        onClearOperationError()
    }
}

@Composable
internal fun HandleGitConflictState(
    syncState: UnifiedSyncState,
    gitFeature: SettingsGitFeatureViewModel,
    dialogState: SettingsDialogState,
    onShowConflictDialog: (com.lomo.domain.model.SyncConflictSet) -> Unit,
) {
    LaunchedEffect(syncState) {
        val errorState = syncState as? UnifiedSyncState.Error
        if (errorState != null) {
            if (errorState.error.provider != SyncBackendType.GIT) return@LaunchedEffect
            val gitErrorCode =
                enumValueOf<com.lomo.domain.model.GitSyncErrorCode>(
                    errorState.error.providerCode ?: com.lomo.domain.model.GitSyncErrorCode.UNKNOWN.name,
                )
            if (
                gitFeature.shouldShowGitConflictDialog(gitErrorCode) &&
                !dialogState.showGitConflictResolutionDialog
            ) {
                dialogState.gitConflictError =
                    SettingsOperationError.GitSync(
                        code = gitErrorCode,
                        detail = errorState.error.message,
                    )
                dialogState.showGitConflictResolutionDialog = true
            }
            return@LaunchedEffect
        }
        val conflictState = syncState as? UnifiedSyncState.ConflictDetected ?: return@LaunchedEffect
        onShowConflictDialog(conflictState.conflicts)
    }
}

@Composable
internal fun HandleWebDavConflictState(
    syncState: UnifiedSyncState,
    onShowConflictDialog: (com.lomo.domain.model.SyncConflictSet) -> Unit,
) {
    LaunchedEffect(syncState) {
        val conflictState = syncState as? UnifiedSyncState.ConflictDetected ?: return@LaunchedEffect
        if (conflictState.provider != SyncBackendType.WEBDAV) return@LaunchedEffect
        onShowConflictDialog(conflictState.conflicts)
    }
}

@Composable
internal fun HandleS3ConflictState(
    syncState: UnifiedSyncState,
    onShowConflictDialog: (com.lomo.domain.model.SyncConflictSet) -> Unit,
) {
    LaunchedEffect(syncState) {
        val conflictState = syncState as? UnifiedSyncState.ConflictDetected ?: return@LaunchedEffect
        if (conflictState.provider != SyncBackendType.S3) return@LaunchedEffect
        onShowConflictDialog(conflictState.conflicts)
    }
}
