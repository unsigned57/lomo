package com.lomo.ui.component.input

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.ui.util.AppHapticFeedback

@Composable
internal fun InputSheetContent(params: InputSheetContentParams) {
    val surface = params.state.surface
    fun dispatchEditorCommand(command: InputEditorCommand) {
        when (command) {
            InputEditorCommand.ToggleTagSelector ->
                params.sessionState.showTagSelector = !params.sessionState.showTagSelector
            InputEditorCommand.InsertTodo ->
                params.callbacks.onInputValueChange(buildTodoInsertionValue(params.inputValue))
            InputEditorCommand.InsertUnderline ->
                params.callbacks.onInputValueChange(buildUnderlineInsertionValue(params.inputValue))
            else -> params.callbacks.commands.dispatch(command)
        }
    }

    InputSheetBody(
        state =
            InputSheetBodyState(
                isSheetVisible = params.sessionState.isSheetVisible,
                showDiscardDialog = params.sessionState.showDiscardDialog,
                surface = surface,
                presentationState = params.presentationState,
                inputValue = params.inputValue,
                hintText = params.hintText,
                showTagSelector = params.sessionState.showTagSelector,
                focusRequester = params.focusRequester,
                focusParkingRequester = params.focusParkingRequester,
            ),
        callbacks =
            InputSheetBodyCallbacks(
                onRequestDismiss = params.requestDismiss,
                onDismissDiscardDialog = { params.sessionState.showDiscardDialog = false },
                onConfirmDiscard = {
                    params.sessionState.showDiscardDialog = false
                    params.dismissSheet()
                },
                onTextChange = params.handleTextChange,
                onTagSelected = { tag ->
                    params.haptic.medium()
                    params.callbacks.onInputValueChange(buildTagInsertionValue(params.inputValue, tag))
                    params.sessionState.showTagSelector = false
                },
                onToggleExpanded = params.callbacks.onToggleExpanded,
                onDisplayModeChange = params.callbacks.onDisplayModeChange,
                onEditorCommand = ::dispatchEditorCommand,
                onToolbarOrderChanged = params.callbacks.onToolbarOrderChanged,
                onSubmit = {
                    if (params.inputValue.text.isNotBlank()) {
                        params.submitWithLock(
                            params.inputValue.text.trim(),
                            params.inputValue.text,
                            params.inputValue.text,
                        )
                    }
                },
            ),
        benchmarkRootTag = params.benchmarkRootTag,
        benchmarkEditorTag = params.benchmarkEditorTag,
        benchmarkSubmitTag = params.benchmarkSubmitTag,
        slots = params.slots,
        haptic = params.haptic,
    )
}

@Composable
private fun InputSheetBody(
    state: InputSheetBodyState,
    callbacks: InputSheetBodyCallbacks,
    benchmarkRootTag: String?,
    benchmarkEditorTag: String?,
    benchmarkSubmitTag: String?,
    slots: InputSheetSlots,
    haptic: AppHapticFeedback,
) {
    if (state.showDiscardDialog) {
        InputDiscardDialog(
            onDismiss = callbacks.onDismissDiscardDialog,
            onConfirmDiscard = callbacks.onConfirmDiscard,
        )
    }

    InputSheetScaffold(
        isSheetVisible = state.isSheetVisible,
        presentationState = state.presentationState,
        scrimAlpha =
            when {
                !state.isSheetVisible -> 0f
                state.presentationState.surfaceMotionStage().usesExpandedSurfaceForm() -> 0.16f
                else -> 0.32f
            },
        onRequestDismiss = callbacks.onRequestDismiss,
        benchmarkRootTag = benchmarkRootTag,
        focusParkingRequester = state.focusParkingRequester,
    ) { motionStage, contentModifier ->
        AnimatedContent(
            modifier = contentModifier,
            targetState = state.surface.recordingState.isRecording,
            transitionSpec = { fadeScaleContentTransition() },
            label = "RecordingStateTransition",
        ) { recording ->
            if (recording) {
                slots.voiceRecordingPanel(
                    VoiceRecordingPanelState(
                        recordingDuration = state.surface.recordingState.durationMillis,
                        recordingAmplitude = state.surface.recordingState.amplitude,
                    ),
                    VoiceRecordingPanelCallbacks(
                        onCancel = {
                            haptic.medium()
                            callbacks.onEditorCommand(InputEditorCommand.CancelRecording)
                        },
                        onStop = {
                            haptic.heavy()
                            callbacks.onEditorCommand(InputEditorCommand.StopRecording)
                        },
                    ),
                )
            } else {
                InputEditorPanel(
                    presentation =
                        InputEditorPanelState(
                            presentationState = state.presentationState,
                            inputValue = state.inputValue,
                            hintText = state.hintText,
                            availableTags = state.surface.availableTags,
                            showTagSelector = state.showTagSelector,
                            focusRequester = state.focusRequester,
                            surface = state.surface,
                            slots = slots,
                        ),
                    callbacks =
                        InputEditorPanelCallbacks(
                            onTextChange = callbacks.onTextChange,
                            onTagSelected = callbacks.onTagSelected,
                            onToggleExpanded = callbacks.onToggleExpanded,
                            onDisplayModeChange = callbacks.onDisplayModeChange,
                            onEditorCommand = callbacks.onEditorCommand,
                            onToolbarOrderChanged = callbacks.onToolbarOrderChanged,
                            onSubmit = callbacks.onSubmit,
                        ),
                    benchmarkEditorTag = benchmarkEditorTag,
                    benchmarkSubmitTag = benchmarkSubmitTag,
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
