package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.feature.memo.MemoEditorCapabilities
import com.lomo.app.feature.memo.MemoEditorCommandHandler
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
    dependencies: MainScreenDependencies,
    interactionCallbacks: MainScreenInteractionCallbacks,
    imageDirectory: String?,
    rootDirectory: String?,
    imageMap: ImmutableMap<String, android.net.Uri>,
    availableTags: ImmutableList<String>,
    inputHints: ImmutableList<String>,
    dateFormat: String,
    timeFormat: String,
    quickSaveOnBackEnabled: Boolean,
    inputToolbarToolOrder: ImmutableList<String>,
    isRecording: Boolean,
    onImageDirectoryMissing: () -> Unit,
    onAttachLocation: () -> Unit,
): MemoEditorSurface {
    val recordingDuration by dependencies.recordingViewModel.recordingDuration.collectAsStateWithLifecycle()
    val recordingAmplitude by dependencies.recordingViewModel.recordingAmplitude.collectAsStateWithLifecycle()
    return MemoEditorSurface(
        session =
            MemoEditorSessionState(
                imageDirectory = imageDirectory,
                rootPath = rootDirectory,
                imageMap = imageMap,
                availableTags = availableTags,
                hints = inputHints,
                isRecording = isRecording,
                recordingDuration = recordingDuration,
                recordingAmplitude = recordingAmplitude,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                onImageDirectoryMissing = onImageDirectoryMissing,
                onCameraCaptureError = interactionCallbacks.onCameraCaptureError,
            ),
        capabilities =
            MemoEditorCapabilities(
                quickSaveOnBackEnabled = quickSaveOnBackEnabled,
                toolbarActions = memoEditorToolbarTools(recording = true, location = true),
                toolbarToolOrder = inputToolbarToolOrder,
            ),
        commands = mainMemoEditorCommands(dependencies, interactionCallbacks, onAttachLocation),
        operations =
            MemoEditorOperations(
                onSaveImage = dependencies.editorViewModel::saveImage,
                onSubmit = { memo, content, timestampMillis ->
                    if (memo != null) {
                        dependencies.editorViewModel.updateMemo(memo, content)
                    } else {
                        interactionCallbacks.onCreateMemo(content, null, timestampMillis)
                    }
                },
                onDismiss = dependencies.editorViewModel::discardInputs,
                onToolbarOrderChanged = { tools ->
                    dependencies.mainViewModel.updateInputToolbarToolOrder(tools.map { tool -> tool.persistedId })
                },
            ),
    )
}

private fun mainMemoEditorCommands(
    dependencies: MainScreenDependencies,
    interactionCallbacks: MainScreenInteractionCallbacks,
    onAttachLocation: () -> Unit,
): MemoEditorCommandHandler =
    MemoEditorCommandHandler { command ->
        when (command) {
            InputEditorCommand.StopRecording -> interactionCallbacks.onStopRecording()
            InputEditorCommand.CancelRecording -> dependencies.recordingViewModel.cancelRecording()
            is InputEditorCommand.Action ->
                when (command.id) {
                    MemoEditorToolbarActionIds.record -> interactionCallbacks.onStartRecording()
                    MemoEditorToolbarActionIds.location -> onAttachLocation()
                    else -> unsupportedMemoEditorCommand(command)
                }
            else -> unsupportedMemoEditorCommand(command)
        }
    }
