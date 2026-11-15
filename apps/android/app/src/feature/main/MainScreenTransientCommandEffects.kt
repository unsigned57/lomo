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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.ExternalAppCommand
import com.lomo.app.ExternalAppCommandTerminalResult
import com.lomo.app.feature.common.PendingUiEvent
import com.lomo.app.feature.memo.MemoEditorController
import com.lomo.app.feature.memo.MemoEditorHostBindings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun MainScreenForegroundAutoInputEffect(
    foregroundEntryId: Long,
    enabled: Boolean,
    uiState: MainViewModel.MainScreenState,
    explicitEntryPending: Boolean,
    editorController: MemoEditorController,
    isRecording: Boolean,
    hasPendingNewMemoCreation: Boolean,
    draftText: StateFlow<String>,
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
internal fun MainScreenForegroundEffects(
    foregroundFlows: MainForegroundEffectFlows,
    externalCommands: MainExternalCommandActions,
    recordingBindings: MainRecordingBindings,
    editorBindings: MemoEditorHostBindings,
    foregroundEntryId: Long,
    autoOpenInputOnForeground: Boolean,
    canOpenCreateMemo: Boolean,
    pendingNewMemoCreationEvent: PendingUiEvent<PendingNewMemoCreationRequest>?,
    directoryGuideController: MainDirectoryGuideController,
    editorController: MemoEditorController,
) {
    val uiState by foregroundFlows.uiState.collectAsStateWithLifecycle()
    val sharedContentEvents by foregroundFlows.sharedContentEvents.collectAsStateWithLifecycle()
    val pendingSharedImageEvents by foregroundFlows.pendingSharedImageEvents.collectAsStateWithLifecycle()
    val appActionEvents by foregroundFlows.appActionEvents.collectAsStateWithLifecycle()
    val externalAppCommands by foregroundFlows.externalAppCommands.collectAsStateWithLifecycle()
    val voiceDirectory by foregroundFlows.voiceDirectory.collectAsStateWithLifecycle()
    val isRecording by recordingBindings.flows.isRecording.collectAsStateWithLifecycle()
    val recordingCaptureId by recordingBindings.flows.recordingCaptureId.collectAsStateWithLifecycle()

    MainScreenForegroundAutoInputEffect(
        foregroundEntryId = foregroundEntryId,
        enabled = autoOpenInputOnForeground,
        uiState = uiState,
        explicitEntryPending =
        sharedContentEvents.isNotEmpty() ||
            pendingSharedImageEvents.isNotEmpty() ||
            appActionEvents.isNotEmpty() ||
            externalAppCommands.any { command -> !command.status.isTerminal },
        editorController = editorController,
        isRecording = isRecording,
        hasPendingNewMemoCreation = pendingNewMemoCreationEvent != null,
        draftText = editorBindings.flows.draftText,
    )

    MainScreenExternalAppCommandEffects(
        executionTarget =
        MainExternalCommandExecutionTarget(
            commandActions = externalCommands,
            recordingActions = recordingBindings.actions,
            editorBindings = editorBindings,
        ),
        externalAppCommands = remember(externalAppCommands) { externalAppCommands.toImmutableList() },
        uiState = uiState,
        voiceDirectoryConfigured = voiceDirectory != null,
        canOpenCreateMemo = canOpenCreateMemo,
        isRecording = isRecording,
        recordingCaptureId = recordingCaptureId,
        draftText = editorBindings.flows.draftText,
        directoryGuideController = directoryGuideController,
        editorController = editorController,
    )
}

@Composable
internal fun MainScreenExternalAppCommandEffects(
    executionTarget: MainExternalCommandExecutionTarget,
    externalAppCommands: ImmutableList<ExternalAppCommand>,
    uiState: MainViewModel.MainScreenState,
    voiceDirectoryConfigured: Boolean,
    canOpenCreateMemo: Boolean,
    isRecording: Boolean,
    recordingCaptureId: String?,
    draftText: StateFlow<String>,
    directoryGuideController: MainDirectoryGuideController,
    editorController: MemoEditorController,
) {
    val context = LocalContext.current
    val latestExecutionTarget = rememberUpdatedState(executionTarget)
    val latestEditorController = rememberUpdatedState(editorController)
    var pendingPermissionCommand by remember { mutableStateOf<ExternalAppCommand?>(null) }
    val recordAudioPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            val command = pendingPermissionCommand
            pendingPermissionCommand = null
            if (command != null) {
                val target = latestExecutionTarget.value
                if (isGranted) {
                    target.recordingActions.onStartRecording(
                        latestEditorController.value.editingSession?.draftId
                            ?: target.editorBindings.flows.getOwnerDraftId(),
                    )
                    target.commandActions.onCompleteExternalAppCommand(
                        command.id,
                        ExternalAppCommandTerminalResult.Executed,
                    )
                } else {
                    target.commandActions.onCompleteExternalAppCommand(
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
            recordingCaptureId = recordingCaptureId,
        ),
        draftText = draftText,
        directoryGuideController = directoryGuideController,
        editorController = latestEditorController.value,
        onRequestRecordAudioPermission = { command ->
            pendingPermissionCommand = command
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        executionTarget = latestExecutionTarget.value,
        permissionRequestInFlightCommandId = pendingPermissionCommand?.id,
    )
}
