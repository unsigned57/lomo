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
    shareCardShowSignature: Boolean,
    shareCardSignatureText: String,
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
    onLanShare:
        ((
            content: String,
            timestamp: Long,
        ) -> Unit)?,
    rootPath: String? = null,
    imageMap: ImmutableMap<String, Uri> = persistentMapOf(),
    dateFormat: String = "yyyy-MM-dd",
    timeFormat: String = "HH:mm",
    onCreateMemo: ((String, String?) -> Unit)? = null,
    onCreateMemoWithTimestamp: ((String, String?, Long?) -> Unit)? = null,
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
    onLocationClick: () -> Unit = {},
    onClearLocation: () -> Unit = {},
    attachedGeoLocation: String? = null,
    hints: ImmutableList<String> = persistentListOf(),
    onVersionHistory: ((MemoMenuState) -> Unit)? = null,
    showJump: Boolean = false,
    showVersionHistory: Boolean = false,
    memoActionAutoReorderEnabled: Boolean = true,
    memoActionOrder: ImmutableList<String> = persistentListOf(),
    onMemoActionInvoked: (MemoActionId) -> Unit = {},
    onMemoActionOrderChanged: (List<MemoActionId>) -> Unit = {},
    inputToolbarToolOrder: ImmutableList<String> = persistentListOf(),
    onInputToolbarToolOrderChanged: (List<String>) -> Unit = {},
    content: @Composable (
        showMenu: (MemoMenuState) -> Unit,
        openEditor: (Memo) -> Unit,
    ) -> Unit,
) {
    MemoMenuBinder(
        shareCardShowTime = shareCardShowTime,
        shareCardShowSignature = shareCardShowSignature,
        shareCardSignatureText = shareCardSignatureText,
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
        onMemoActionOrderChanged = onMemoActionOrderChanged,
    ) { showMenu ->
        content(showMenu, controller::openForEdit)

        MemoEditorSheetHost(
            controller = controller,
            imageDirectory = imageDirectory,
            rootPath = rootPath,
            imageMap = imageMap,
            quickSaveOnBackEnabled = quickSaveOnBackEnabled,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            onSaveImage = onSaveImage,
            onSubmit = { memo, content, timestampMillis ->
                if (memo != null) {
                    onUpdateMemo(memo, content)
                } else {
                    onCreateMemoWithTimestamp?.invoke(content, attachedGeoLocation, timestampMillis)
                        ?: onCreateMemo?.invoke(content, attachedGeoLocation)
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
            onLocationClick = onLocationClick,
            onClearLocation = onClearLocation,
            attachedGeoLocation = attachedGeoLocation,
            hints = hints,
            inputToolbarToolOrder = inputToolbarToolOrder,
            onInputToolbarToolOrderChanged = onInputToolbarToolOrderChanged,
        )
    }
}
