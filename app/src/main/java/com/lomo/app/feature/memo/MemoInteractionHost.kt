package com.lomo.app.feature.memo

import android.net.Uri
import androidx.compose.runtime.Composable
import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoMenuState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun MemoInteractionHost(
    shareCardShowTime: Boolean,
    activeDayCount: Int,
    imageDirectory: String?,
    controller: MemoEditorController = rememberMemoEditorController(),
    quickSaveOnBackEnabled: Boolean = false,
    onDeleteMemo: (Memo) -> Unit,
    onUpdateMemo: (
        memo: Memo,
        content: String,
    ) -> Unit,
    onCreateMemo: ((String) -> Unit)? = null,
    onSaveImage: (
        uri: Uri,
        onResult: (String) -> Unit,
        onError: (() -> Unit)?,
    ) -> Unit,
    onLanShare: (
        content: String,
        timestamp: Long,
    ) -> Unit,
    onTogglePin: ((Memo, Boolean) -> Unit)? = null,
    onJump: ((MemoMenuState) -> Unit)? = null,
    onDismiss: () -> Unit = {},
    onImageDirectoryMissing: (() -> Unit)? = null,
    onCameraCaptureError: ((Throwable) -> Unit)? = null,
    availableTags: List<String> = emptyList(),
    isRecording: Boolean = false,
    recordingDuration: Long = 0L,
    recordingAmplitude: Int = 0,
    isRecordingFlow: StateFlow<Boolean>? = null,
    recordingDurationFlow: StateFlow<Long>? = null,
    recordingAmplitudeFlow: StateFlow<Int>? = null,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    hints: List<String> = emptyList(),
    onVersionHistory: ((MemoMenuState) -> Unit)? = null,
    showJump: Boolean = false,
    showVersionHistory: Boolean = false,
    content: @Composable (
        showMenu: (MemoMenuState) -> Unit,
        openEditor: (Memo) -> Unit,
    ) -> Unit,
) {
    MemoMenuBinder(
        shareCardShowTime = shareCardShowTime,
        activeDayCount = activeDayCount,
        onEditMemo = controller::openForEdit,
        onDeleteMemo = onDeleteMemo,
        onLanShare = onLanShare,
        onTogglePin = onTogglePin,
        onJump = onJump,
        onVersionHistory = onVersionHistory,
        showJump = showJump,
        showVersionHistory = showVersionHistory,
    ) { showMenu ->
        content(showMenu, controller::openForEdit)

        MemoEditorSheetHost(
            controller = controller,
            imageDirectory = imageDirectory,
            quickSaveOnBackEnabled = quickSaveOnBackEnabled,
            onSaveImage = onSaveImage,
            onSubmit = { memo, content ->
                if (memo != null) {
                    onUpdateMemo(memo, content)
                } else {
                    onCreateMemo?.invoke(content)
                }
            },
            onDismiss = onDismiss,
            onImageDirectoryMissing = onImageDirectoryMissing,
            onCameraCaptureError = onCameraCaptureError,
            availableTags = availableTags,
            isRecording = isRecording,
            recordingDuration = recordingDuration,
            recordingAmplitude = recordingAmplitude,
            isRecordingFlow = isRecordingFlow,
            recordingDurationFlow = recordingDurationFlow,
            recordingAmplitudeFlow = recordingAmplitudeFlow,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            hints = hints,
        )
    }
}
