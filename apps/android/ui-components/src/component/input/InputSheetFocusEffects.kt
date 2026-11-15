package com.lomo.ui.component.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.SoftwareKeyboardController

internal const val INPUT_SHEET_FOCUS_REQUEST_MAX_ATTEMPTS = 5
internal const val INPUT_SHEET_FOCUS_RELEASE_MAX_ATTEMPTS = 5

@Composable
internal fun InputSheetVisibilityEffects(focusRequestToken: Long, onSheetVisibleChange: (Boolean) -> Unit) {
    LaunchedEffect(focusRequestToken) {
        onSheetVisibleChange(true)
    }
}

@Composable
internal fun InputSheetFocusRequestEffects(state: InputSheetFocusRequestState) {
    val currentState by rememberUpdatedState(state)
    var lastHandledFocusRequestToken by remember { mutableLongStateOf(Long.MIN_VALUE) }

    LaunchedEffect(
        state.sessionState.isSheetVisible,
        state.editorAcceptsFocus,
        state.isRecording,
        state.sessionState.isDismissing,
        state.keyboardController,
    ) {
        if (!state.sessionState.isSheetVisible) return@LaunchedEffect
        when {
            state.sessionState.isDismissing -> {
                releaseEditorFocusAndKeyboard(
                    keyboardController = state.keyboardController,
                    focusParkingRequester = state.focusParkingRequester,
                )
            }

            // The keyboard leaves while preview fades in, so both motions read as one switch.
            !state.editorAcceptsFocus -> {
                releaseEditorFocusAndKeyboard(
                    keyboardController = state.keyboardController,
                    focusParkingRequester = state.focusParkingRequester,
                )
            }

            state.isRecording -> state.keyboardController?.hide()
        }
    }

    LaunchedEffect(
        state.sessionState.isSheetVisible,
        state.editorAcceptsFocus,
        state.isRecording,
        state.sessionState.isDismissing,
        state.focusRequestToken,
    ) {
        if (
            !shouldRequestInputSheetEditorFocus(
                isSheetVisible = state.sessionState.isSheetVisible,
                editorAcceptsFocus = state.editorAcceptsFocus,
                isRecording = state.isRecording,
                isDismissing = state.sessionState.isDismissing,
                focusRequestToken = state.focusRequestToken,
                lastHandledFocusRequestToken = lastHandledFocusRequestToken,
            )
        ) {
            return@LaunchedEffect
        }
        val shown =
            requestEditorFocusAndKeyboard(
                focusRequester = state.focusRequester,
                keyboardController = state.keyboardController,
                isActive = {
                    val latest = currentState
                    latest.focusRequestToken == state.focusRequestToken &&
                        latest.sessionState.isSheetVisible &&
                        !latest.sessionState.isDismissing &&
                        !latest.isRecording &&
                        latest.editorAcceptsFocus
                },
            )
        if (shown) {
            lastHandledFocusRequestToken = state.focusRequestToken
        }
    }
}

internal fun shouldRequestInputSheetEditorFocus(
    isSheetVisible: Boolean,
    editorAcceptsFocus: Boolean,
    isRecording: Boolean,
    isDismissing: Boolean,
    focusRequestToken: Long,
    lastHandledFocusRequestToken: Long,
): Boolean = isSheetVisible &&
    editorAcceptsFocus &&
    !isRecording &&
    !isDismissing &&
    focusRequestToken != lastHandledFocusRequestToken

internal fun releaseEditorFocusAndKeyboardImmediately(
    keyboardController: SoftwareKeyboardController?,
    focusParkingRequester: FocusRequester,
) {
    releaseEditorWindowFocus(focusParkingRequester = focusParkingRequester)
    keyboardController?.hide()
}
