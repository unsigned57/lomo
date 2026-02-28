package com.lomo.app.feature.memo

import android.net.Uri
import androidx.compose.runtime.Composable
import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoMenuState

@Composable
fun MemoInteractionHost(
    shareCardStyle: String,
    shareCardShowTime: Boolean,
    activeDayCount: Int,
    imageDirectory: String?,
    controller: MemoEditorController = rememberMemoEditorController(),
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
    onDismiss: () -> Unit = {},
    onImageDirectoryMissing: (() -> Unit)? = null,
    onCameraCaptureError: ((Throwable) -> Unit)? = null,
    availableTags: List<String> = emptyList(),
    isRecording: Boolean = false,
    recordingDuration: Long = 0L,
    recordingAmplitude: Int = 0,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    hints: List<String> = emptyList(),
    onVersionHistory: ((MemoMenuState) -> Unit)? = null,
    showVersionHistory: Boolean = false,
    content: @Composable (
        showMenu: (MemoMenuState) -> Unit,
        openEditor: (Memo) -> Unit,
    ) -> Unit,
) {
    MemoMenuBinder(
        shareCardStyle = shareCardStyle,
        shareCardShowTime = shareCardShowTime,
        activeDayCount = activeDayCount,
        onEditMemo = controller::openForEdit,
        onDeleteMemo = onDeleteMemo,
        onLanShare = onLanShare,
        onVersionHistory = onVersionHistory,
        showVersionHistory = showVersionHistory,
    ) { showMenu ->
        content(showMenu, controller::openForEdit)

        MemoEditorSheetHost(
            controller = controller,
            imageDirectory = imageDirectory,
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
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            hints = hints,
        )
    }
}
