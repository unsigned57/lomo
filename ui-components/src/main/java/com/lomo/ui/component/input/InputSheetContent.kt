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
    presentationState: InputSheetPresentationState,
    inputValue: TextFieldValue,
    previewContent: String?,
    hintText: String,
    focusRequester: FocusRequester,
    focusParkingRequester: FocusRequester,
    haptic: AppHapticFeedback,
    dismissSheet: () -> Unit,
    requestDismiss: () -> Unit,
    handleTextChange: (TextFieldValue) -> Unit,
    submitWithLock: (String, String, String) -> Unit,
    benchmarkRootTag: String?,
    benchmarkEditorTag: String?,
    benchmarkSubmitTag: String?,
) {
    val surface = state.surface
    fun dispatchEditorCommand(command: InputEditorCommand) {
        when (command) {
            InputEditorCommand.ToggleTagSelector -> sessionState.showTagSelector = !sessionState.showTagSelector
            InputEditorCommand.InsertTodo -> callbacks.onInputValueChange(buildTodoInsertionValue(inputValue))
            InputEditorCommand.InsertUnderline -> callbacks.onInputValueChange(buildUnderlineInsertionValue(inputValue))
            else -> callbacks.commands.dispatch(command)
        }
    }

    InputSheetBody(
        isSheetVisible = sessionState.isSheetVisible,
        onRequestDismiss = requestDismiss,
        showDiscardDialog = sessionState.showDiscardDialog,
        onDismissDiscardDialog = { sessionState.showDiscardDialog = false },
        onConfirmDiscard = {
            sessionState.showDiscardDialog = false
            dismissSheet()
        },
        surface = surface,
        presentationState = presentationState,
        inputValue = inputValue,
        previewContent = previewContent,
        hintText = hintText,
        showTagSelector = sessionState.showTagSelector,
        focusRequester = focusRequester,
        focusParkingRequester = focusParkingRequester,
        onTextChange = handleTextChange,
        onTagSelected = { tag ->
            haptic.medium()
            callbacks.onInputValueChange(buildTagInsertionValue(inputValue, tag))
            sessionState.showTagSelector = false
        },
        onToggleExpanded = callbacks.onToggleExpanded,
        onDisplayModeChange = callbacks.onDisplayModeChange,
        onEditorCommand = ::dispatchEditorCommand,
        onToolbarOrderChanged = callbacks.onToolbarOrderChanged,
        onSubmit = {
            if (inputValue.text.isNotBlank()) {
                submitWithLock(inputValue.text.trim(), inputValue.text, inputValue.text)
            }
        },
        benchmarkRootTag = benchmarkRootTag,
        benchmarkEditorTag = benchmarkEditorTag,
        benchmarkSubmitTag = benchmarkSubmitTag,
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
    surface: InputEditorSurfaceState,
    presentationState: InputSheetPresentationState,
    inputValue: TextFieldValue,
    previewContent: String?,
    hintText: String,
    showTagSelector: Boolean,
    focusRequester: FocusRequester,
    focusParkingRequester: FocusRequester,
    onTextChange: (TextFieldValue) -> Unit,
    onTagSelected: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    onDisplayModeChange: (InputEditorDisplayMode) -> Unit,
    onEditorCommand: (InputEditorCommand) -> Unit,
    onToolbarOrderChanged: (List<InputToolbarActionId>) -> Unit,
    onSubmit: () -> Unit,
    benchmarkRootTag: String?,
    benchmarkEditorTag: String?,
    benchmarkSubmitTag: String?,
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
        presentationState = presentationState,
        scrimAlpha =
            when {
                !isSheetVisible -> 0f
                presentationState.surfaceMotionStage().usesExpandedSurfaceForm() -> 0.16f
                else -> 0.32f
            },
        onRequestDismiss = onRequestDismiss,
        benchmarkRootTag = benchmarkRootTag,
        focusParkingRequester = focusParkingRequester,
    ) { motionStage, contentModifier ->
        AnimatedContent(
            modifier = contentModifier,
            targetState = surface.recordingState.isRecording,
            transitionSpec = { fadeScaleContentTransition() },
            label = "RecordingStateTransition",
        ) { recording ->
            if (recording) {
                slots.voiceRecordingPanel(
                    VoiceRecordingPanelState(
                        recordingDuration = surface.recordingState.durationMillis,
                        recordingAmplitude = surface.recordingState.amplitude,
                    ),
                    VoiceRecordingPanelCallbacks(
                        onCancel = {
                            haptic.medium()
                            onEditorCommand(InputEditorCommand.CancelRecording)
                        },
                        onStop = {
                            haptic.heavy()
                            onEditorCommand(InputEditorCommand.StopRecording)
                        },
                    ),
                )
            } else {
                InputEditorPanel(
                    presentationState = presentationState,
                    inputValue = inputValue,
                    previewContent = previewContent,
                    hintText = hintText,
                    availableTags = surface.availableTags,
                    showTagSelector = showTagSelector,
                    focusRequester = focusRequester,
                    onTextChange = onTextChange,
                    onTagSelected = onTagSelected,
                    onToggleExpanded = onToggleExpanded,
                    onDisplayModeChange = onDisplayModeChange,
                    surface = surface,
                    onEditorCommand = onEditorCommand,
                    onToolbarOrderChanged = onToolbarOrderChanged,
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

internal fun buildTagInsertionValue(
    inputValue: TextFieldValue,
    tag: String,
): TextFieldValue {
    val selectionStart = minOf(inputValue.selection.start, inputValue.selection.end).coerceIn(0, inputValue.text.length)
    val selectionEnd = maxOf(inputValue.selection.start, inputValue.selection.end).coerceIn(0, inputValue.text.length)
    val prefix = inputValue.text.substring(0, selectionStart)
    val rawSuffix = inputValue.text.substring(selectionEnd)
    val leadingSeparator =
        if (prefix.isNotEmpty() && !prefix.last().isWhitespace()) {
            " "
        } else {
            ""
        }
    val insertion = "$leadingSeparator#$tag "
    val suffix =
        if (rawSuffix.firstOrNull()?.isWhitespace() == true) {
            rawSuffix.drop(1)
        } else {
            rawSuffix
        }
    val newText = prefix + insertion + suffix
    return TextFieldValue(newText, TextRange(selectionStart + insertion.length))
}

private fun buildTodoInsertionValue(inputValue: TextFieldValue): TextFieldValue {
    val cursorPos = inputValue.selection.start.coerceIn(0, inputValue.text.length)
    val prefix = inputValue.text.substring(0, cursorPos)
    val suffix = inputValue.text.substring(cursorPos)
    val todoMarker = "- [ ] "
    val needsNewline = prefix.isNotEmpty() && !prefix.endsWith('\n')
    val insertion = if (needsNewline) "\n$todoMarker" else todoMarker
    val newText = prefix + insertion + suffix
    val cursorTarget = cursorPos + insertion.length
    return TextFieldValue(newText, TextRange(cursorTarget))
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
