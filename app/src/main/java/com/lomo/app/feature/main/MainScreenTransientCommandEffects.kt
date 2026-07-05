package com.lomo.app.feature.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.lomo.app.ExternalAppCommand
import com.lomo.app.ExternalAppCommandTerminalResult
import com.lomo.app.feature.memo.MemoEditorController
import kotlinx.collections.immutable.ImmutableList

@Composable
internal fun MainScreenForegroundAutoInputEffect(
    foregroundEntryId: Long,
    enabled: Boolean,
    uiState: MainViewModel.MainScreenState,
    explicitEntryPending: Boolean,
    editorController: MemoEditorController,
    isRecording: Boolean,
    hasPendingNewMemoCreation: Boolean,
    draftText: String,
) {
    MainForegroundAutoInputEffect(
        foregroundEntryId = foregroundEntryId,
        enabled = enabled,
        uiState = uiState,
        explicitEntryPending = explicitEntryPending,
        editorVisible = editorController.isVisible,
        isRecording = isRecording,
        hasPendingNewMemoCreation = hasPendingNewMemoCreation,
        draftText = draftText,
        onOpenDraftEditor = editorController::openForCreate,
        onRefocusEditor = editorController::ensureVisible,
    )
}

@Composable
internal fun MainScreenExternalAppCommandEffects(
    dependencies: MainScreenDependencies,
    externalAppCommands: ImmutableList<ExternalAppCommand>,
    uiState: MainViewModel.MainScreenState,
    voiceDirectoryConfigured: Boolean,
    canOpenCreateMemo: Boolean,
    isRecording: Boolean,
    draftText: String,
    directoryGuideController: MainDirectoryGuideController,
    editorController: MemoEditorController,
) {
    val context = LocalContext.current
    val latestDependencies = rememberUpdatedState(dependencies)
    val latestEditorController = rememberUpdatedState(editorController)
    var pendingPermissionCommand by remember { mutableStateOf<ExternalAppCommand?>(null) }
    val recordAudioPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            val command = pendingPermissionCommand
            pendingPermissionCommand = null
            if (command != null) {
                if (isGranted) {
                    latestDependencies.value.recordingViewModel.startRecording()
                    latestDependencies.value.mainViewModel.completeExternalAppCommand(
                        command.id,
                        ExternalAppCommandTerminalResult.Executed,
                    )
                } else {
                    latestDependencies.value.mainViewModel.completeExternalAppCommand(
                        command.id,
                        ExternalAppCommandTerminalResult.Failed,
                    )
                }
            }
        }

    fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    ExternalAppCommandExecutionEffect(
        commands = externalAppCommands,
        readiness =
            ExternalAppCommandReadiness(
                appReady = uiState is MainViewModel.MainScreenState.Ready,
                voiceDirectoryConfigured = voiceDirectoryConfigured,
                editorVisible = editorController.isVisible,
                canOpenDraftEditor = canOpenCreateMemo,
                hasRecordAudioPermission = hasRecordAudioPermission(),
                isRecording = isRecording,
            ),
        draftText = draftText,
        directoryGuideController = directoryGuideController,
        editorController = latestEditorController.value,
        onRequestRecordAudioPermission = { command ->
            pendingPermissionCommand = command
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        dependencies = latestDependencies.value,
        permissionRequestInFlightCommandId = pendingPermissionCommand?.id,
    )
}
