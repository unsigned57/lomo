package com.lomo.ui.component.input

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.ui.util.AppHapticFeedback
import kotlinx.collections.immutable.ImmutableList

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
        isExpanded = state.isExpanded,
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
        onToggleExpanded = callbacks.onToggleExpanded,
        onToggleTagSelector = { sessionState.showTagSelector = !sessionState.showTagSelector },
        onCameraClick = callbacks.onCameraClick,
        onImageClick = callbacks.onImageClick,
        onStartRecording = callbacks.onStartRecording,
        onInsertTodo = { callbacks.onInputValueChange(buildTodoInsertionValue(inputValue.text)) },
        onInsertUnderline = { callbacks.onInputValueChange(buildUnderlineInsertionValue(inputValue)) },
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
    isExpanded: Boolean,
    inputValue: TextFieldValue,
    hintText: String,
    availableTags: ImmutableList<String>,
    showTagSelector: Boolean,
    focusRequester: FocusRequester,
    focusParkingRequester: FocusRequester,
    onEditorReady: (MemoInputEditText) -> Unit,
    onTextChange: (TextFieldValue) -> Unit,
    onTagSelected: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    onToggleTagSelector: () -> Unit,
    onCameraClick: () -> Unit,
    onImageClick: () -> Unit,
    onStartRecording: () -> Unit,
    onInsertTodo: () -> Unit,
    onInsertUnderline: () -> Unit,
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
        isExpanded = isExpanded,
        scrimAlpha =
            when {
                !isSheetVisible -> 0f
                isExpanded -> 0.16f
                else -> 0.32f
            },
        onRequestDismiss = onRequestDismiss,
        benchmarkRootTag = benchmarkRootTag,
        focusParkingRequester = focusParkingRequester,
    ) { motionStage, contentModifier ->
        AnimatedContent(
            modifier = contentModifier,
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
                    motionStage = motionStage,
                    inputValue = inputValue,
                    hintText = hintText,
                    availableTags = availableTags,
                    showTagSelector = showTagSelector,
                    focusRequester = focusRequester,
                    onEditorReady = onEditorReady,
                    onTextChange = onTextChange,
                    onTagSelected = onTagSelected,
                    onToggleExpanded = onToggleExpanded,
                    onToggleTagSelector = onToggleTagSelector,
                    onCameraClick = onCameraClick,
                    onImageClick = onImageClick,
                    onStartRecording = onStartRecording,
                    onInsertTodo = onInsertTodo,
                    onInsertUnderline = onInsertUnderline,
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

internal fun buildUnderlineInsertionValue(inputValue: TextFieldValue): TextFieldValue =
    buildWrappedSelectionInsertionValue(
        inputValue = inputValue,
        prefix = "<u>",
        suffix = "</u>",
    )

internal fun buildWrappedSelectionInsertionValue(
    inputValue: TextFieldValue,
    prefix: String,
    suffix: String,
): TextFieldValue {
    val selectionStart = minOf(inputValue.selection.start, inputValue.selection.end)
    val selectionEnd = maxOf(inputValue.selection.start, inputValue.selection.end)
    val selectedText = inputValue.text.substring(selectionStart, selectionEnd)
    val replacementText = prefix + selectedText + suffix
    val newText =
        buildString {
            append(inputValue.text.substring(0, selectionStart))
            append(replacementText)
            append(inputValue.text.substring(selectionEnd))
        }
    val innerSelectionStart = selectionStart + prefix.length
    val innerSelectionEnd = innerSelectionStart + selectedText.length
    return TextFieldValue(
        text = newText,
        selection =
            if (selectionStart == selectionEnd) {
                TextRange(innerSelectionStart)
            } else {
                TextRange(innerSelectionStart, innerSelectionEnd)
            },
    )
}
