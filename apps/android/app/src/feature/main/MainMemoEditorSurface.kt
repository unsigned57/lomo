package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.feature.memo.MemoEditorCapabilities
import com.lomo.app.feature.memo.MemoEditorCommandHandler
import com.lomo.app.feature.memo.MemoEditorHostBindings
import com.lomo.app.feature.memo.MemoEditorOperations
import com.lomo.app.feature.memo.MemoEditorSessionState
import com.lomo.app.feature.memo.MemoEditorSurface
import com.lomo.app.feature.memo.MemoEditorToolbarActionIds
import com.lomo.app.feature.memo.memoEditorToolbarTools
import com.lomo.app.feature.memo.unsupportedMemoEditorCommand
import com.lomo.ui.component.input.InputEditorCommand
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap

@Composable
internal fun rememberMainMemoEditorSurface(
    editorBindings: MemoEditorHostBindings,
    recordingBindings: MainRecordingBindings,
    onUpdateInputToolbarToolOrder: (List<String>) -> Unit,
    interactionCallbacks: MainScreenInteractionCallbacks,
    imageDirectory: String?,
    rootDirectory: String?,
    imageMap: ImmutableMap<String, android.net.Uri>,
    availableTags: ImmutableList<String>,
    inputHints: ImmutableList<String>,
    quickSaveOnBackEnabled: Boolean,
    inputToolbarToolOrder: ImmutableList<String>,
    isRecording: Boolean,
    onImageDirectoryMissing: () -> Unit,
): MemoEditorSurface {
    val recordingDuration by recordingBindings.flows.recordingDuration.collectAsStateWithLifecycle()
    val recordingAmplitude by recordingBindings.flows.recordingAmplitude.collectAsStateWithLifecycle()
    return MemoEditorSurface(
        session =
        MemoEditorSessionState(
            imageDirectory = imageDirectory,
            ownerDraftId = editorBindings.flows.getOwnerDraftId(),
            rootPath = rootDirectory,
            imageMap = imageMap,
            availableTags = availableTags,
            hints = inputHints,
            isRecording = isRecording,
            recordingDuration = recordingDuration,
            recordingAmplitude = recordingAmplitude,
            onImageDirectoryMissing = onImageDirectoryMissing,
            onCameraCaptureError = interactionCallbacks.onCameraCaptureError,
        ),
        capabilities =
        MemoEditorCapabilities(
            quickSaveOnBackEnabled = quickSaveOnBackEnabled,
            toolbarActions = memoEditorToolbarTools(recording = true),
            toolbarToolOrder = inputToolbarToolOrder,
        ),
        commands =
        mainMemoEditorCommands(
            onCancelRecording = recordingBindings.actions.onCancelRecording,
            interactionCallbacks = interactionCallbacks,
        ),
        operations =
        MemoEditorOperations(
            onSaveImages = editorBindings.actions.onSaveImages,
            onSubmit = { submissionId, session, content, timestampMillis ->
                if (session != null) {
                    editorBindings.submissions.update(submissionId, session, content)
                    editorBindings.submissions.await(submissionId)
                } else {
                    val accepted =
                        interactionCallbacks.onCreateMemo(
                            submissionId,
                            content,
                            timestampMillis,
                        )
                    if (!accepted) {
                        editorBindings.submissions.reject(
                            submissionId,
                            IllegalStateException("Another memo creation is already pending"),
                        )
                        false
                    } else {
                        editorBindings.submissions.await(submissionId)
                    }
                }
            },
            submissionState = editorBindings.flows.submissionState,
            onDismiss = editorBindings.actions.onDiscardInputs,
            onToolbarOrderChanged = { tools ->
                onUpdateInputToolbarToolOrder(tools.map { tool -> tool.persistedId })
            },
        ),
    )
}

private fun mainMemoEditorCommands(
    onCancelRecording: () -> Unit,
    interactionCallbacks: MainScreenInteractionCallbacks,
): MemoEditorCommandHandler = MemoEditorCommandHandler { command ->
    when (command) {
        InputEditorCommand.StopRecording -> interactionCallbacks.onStopRecording()

        InputEditorCommand.CancelRecording -> onCancelRecording()

        is InputEditorCommand.Action ->
            when (command.id) {
                MemoEditorToolbarActionIds.record -> interactionCallbacks.onStartRecording()
                else -> unsupportedMemoEditorCommand(command)
            }

        else -> unsupportedMemoEditorCommand(command)
    }
}
