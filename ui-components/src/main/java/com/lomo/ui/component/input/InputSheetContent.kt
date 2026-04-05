package com.lomo.ui.component.input

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.ui.util.AppHapticFeedback

@Composable
internal fun InputSheetContent(
    state: InputSheetState,
    callbacks: InputSheetCallbacks,
    slots: InputSheetSlots,
    sessionState: InputSheetSessionState,
    inputValue: TextFieldValue,
    hintText: String,
    focusRequester: FocusRequester,
    focusParkingRequester: FocusRequester,
    onEditorReady: (MemoInputEditText) -> Unit,
    haptic: AppHapticFeedback,
    dismissSheet: () -> Unit,
    requestDismiss: () -> Unit,
    handleTextChange: (TextFieldValue) -> Unit,
    submitWithLock: (String, String, String) -> Unit,
    benchmarkRootTag: String?,
    benchmarkEditorTag: String?,
    benchmarkSubmitTag: String?,
) {
    InputSheetBody(
        isSheetVisible = sessionState.isSheetVisible,
        onRequestDismiss = requestDismiss,
        showDiscardDialog = sessionState.showDiscardDialog,
        onDismissDiscardDialog = { sessionState.showDiscardDialog = false },
        onConfirmDiscard = {
            sessionState.showDiscardDialog = false
            dismissSheet()
        },
        isRecording = state.isRecording,
        recordingDuration = state.recordingDuration,
        recordingAmplitude = state.recordingAmplitude,
        inputValue = inputValue,
        hintText = hintText,
        availableTags = state.availableTags,
        showTagSelector = sessionState.showTagSelector,
        focusRequester = focusRequester,
        focusParkingRequester = focusParkingRequester,
        onEditorReady = onEditorReady,
        onTextChange = handleTextChange,
        onTagSelected = { tag ->
            haptic.medium()
            callbacks.onInputValueChange(buildTagInsertionValue(inputValue.text, tag))
            sessionState.showTagSelector = false
        },
        onToggleTagSelector = { sessionState.showTagSelector = !sessionState.showTagSelector },
        onCameraClick = callbacks.onCameraClick,
        onImageClick = callbacks.onImageClick,
        onStartRecording = callbacks.onStartRecording,
        onInsertTodo = { callbacks.onInputValueChange(buildTodoInsertionValue(inputValue.text)) },
        onSubmit = {
            if (inputValue.text.isNotBlank()) {
                submitWithLock(inputValue.text.trim(), inputValue.text, inputValue.text)
            }
        },
        benchmarkRootTag = benchmarkRootTag,
        benchmarkEditorTag = benchmarkEditorTag,
        benchmarkSubmitTag = benchmarkSubmitTag,
        onCancelRecording = callbacks.onCancelRecording,
        onStopRecording = callbacks.onStopRecording,
        slots = slots,
        haptic = haptic,
    )
}

@Composable
private fun InputSheetBody(
    isSheetVisible: Boolean,
    onRequestDismiss: () -> Unit,
    showDiscardDialog: Boolean,
    onDismissDiscardDialog: () -> Unit,
    onConfirmDiscard: () -> Unit,
    isRecording: Boolean,
    recordingDuration: Long,
    recordingAmplitude: Int,
    inputValue: TextFieldValue,
    hintText: String,
    availableTags: List<String>,
    showTagSelector: Boolean,
    focusRequester: FocusRequester,
    focusParkingRequester: FocusRequester,
    onEditorReady: (MemoInputEditText) -> Unit,
    onTextChange: (TextFieldValue) -> Unit,
    onTagSelected: (String) -> Unit,
    onToggleTagSelector: () -> Unit,
    onCameraClick: () -> Unit,
    onImageClick: () -> Unit,
    onStartRecording: () -> Unit,
    onInsertTodo: () -> Unit,
    onSubmit: () -> Unit,
    benchmarkRootTag: String?,
    benchmarkEditorTag: String?,
    benchmarkSubmitTag: String?,
    onCancelRecording: () -> Unit,
    onStopRecording: () -> Unit,
    slots: InputSheetSlots,
    haptic: AppHapticFeedback,
) {
    if (showDiscardDialog) {
        InputDiscardDialog(
            onDismiss = onDismissDiscardDialog,
            onConfirmDiscard = onConfirmDiscard,
        )
    }

    InputSheetScaffold(
        isSheetVisible = isSheetVisible,
        scrimAlpha = if (isSheetVisible) 0.32f else 0f,
        onRequestDismiss = onRequestDismiss,
        benchmarkRootTag = benchmarkRootTag,
        focusParkingRequester = focusParkingRequester,
    ) {
        AnimatedContent(
            targetState = isRecording,
            transitionSpec = { fadeScaleContentTransition() },
            label = "RecordingStateTransition",
        ) { recording ->
            if (recording) {
                slots.voiceRecordingPanel(
                    VoiceRecordingPanelState(
                        recordingDuration = recordingDuration,
                        recordingAmplitude = recordingAmplitude,
                    ),
                    VoiceRecordingPanelCallbacks(
                        onCancel = {
                            haptic.medium()
                            onCancelRecording()
                        },
                        onStop = {
                            haptic.heavy()
                            onStopRecording()
                        },
                    ),
                )
            } else {
                InputEditorPanel(
                    inputValue = inputValue,
                    hintText = hintText,
                    availableTags = availableTags,
                    showTagSelector = showTagSelector,
                    focusRequester = focusRequester,
                    onEditorReady = onEditorReady,
                    onTextChange = onTextChange,
                    onTagSelected = onTagSelected,
                    onToggleTagSelector = onToggleTagSelector,
                    onCameraClick = onCameraClick,
                    onImageClick = onImageClick,
                    onStartRecording = onStartRecording,
                    onInsertTodo = onInsertTodo,
                    onSubmit = onSubmit,
                    benchmarkEditorTag = benchmarkEditorTag,
                    benchmarkSubmitTag = benchmarkSubmitTag,
                    slots = slots,
                    haptic = haptic,
                )
            }
        }
    }
}

private fun buildTagInsertionValue(
    inputText: String,
    tag: String,
): TextFieldValue {
    val newText = "$inputText #$tag "
    return TextFieldValue(newText, TextRange(newText.length))
}

private fun buildTodoInsertionValue(inputText: String): TextFieldValue {
    val newText = if (inputText.isEmpty()) "- [ ] " else "$inputText\n- [ ] "
    return TextFieldValue(newText, TextRange(newText.length))
}
