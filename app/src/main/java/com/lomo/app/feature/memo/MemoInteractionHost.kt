package com.lomo.app.feature.memo

import android.net.Uri
import androidx.compose.runtime.Composable
import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoActionId
import com.lomo.ui.component.menu.MemoMenuState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.StateFlow

@Composable
fun MemoInteractionHost(
    shareCardShowTime: Boolean,
    activeDayCount: Int,
    imageDirectory: String?,
    onDeleteMemo: (Memo) -> Unit,
    onUpdateMemo: (
        memo: Memo,
        content: String,
    ) -> Unit,
    onSaveImage: (
        uri: Uri,
        onResult: (String) -> Unit,
        onError: (() -> Unit)?,
    ) -> Unit,
    onLanShare: (
        content: String,
        timestamp: Long,
    ) -> Unit,
    rootPath: String? = null,
    imageMap: ImmutableMap<String, Uri> = persistentMapOf(),
    onCreateMemo: ((String) -> Unit)? = null,
    controller: MemoEditorController = rememberMemoEditorController(),
    quickSaveOnBackEnabled: Boolean = false,
    onTogglePin: ((Memo, Boolean) -> Unit)? = null,
    onJump: ((MemoMenuState) -> Unit)? = null,
    onDismiss: () -> Unit = {},
    onImageDirectoryMissing: (() -> Unit)? = null,
    onCameraCaptureError: ((Throwable) -> Unit)? = null,
    availableTags: ImmutableList<String> = persistentListOf(),
    isRecording: Boolean = false,
    recordingDuration: Long = 0L,
    recordingAmplitude: Int = 0,
    isRecordingFlow: StateFlow<Boolean>? = null,
    recordingDurationFlow: StateFlow<Long>? = null,
    recordingAmplitudeFlow: StateFlow<Int>? = null,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    hints: ImmutableList<String> = persistentListOf(),
    onVersionHistory: ((MemoMenuState) -> Unit)? = null,
    showJump: Boolean = false,
    showVersionHistory: Boolean = false,
    memoActionAutoReorderEnabled: Boolean = true,
    memoActionOrder: ImmutableList<String> = persistentListOf(),
    onMemoActionInvoked: (MemoActionId) -> Unit = {},
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
        memoActionAutoReorderEnabled = memoActionAutoReorderEnabled,
        memoActionOrder = memoActionOrder,
        onMemoActionInvoked = onMemoActionInvoked,
    ) { showMenu ->
        content(showMenu, controller::openForEdit)

        MemoEditorSheetHost(
            controller = controller,
            imageDirectory = imageDirectory,
            rootPath = rootPath,
            imageMap = imageMap,
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
