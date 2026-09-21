package com.lomo.ui.component.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.SoftwareKeyboardController
import com.lomo.ui.theme.MotionTokens
import kotlinx.coroutines.delay

internal const val INPUT_SHEET_FOCUS_REQUEST_MAX_ATTEMPTS = 5
internal const val INPUT_SHEET_FOCUS_RELEASE_MAX_ATTEMPTS = 5
internal const val INPUT_SHEET_ENTRY_SETTLE_DELAY_MILLIS = MotionTokens.DurationLong2.toLong()

@Composable
internal fun InputSheetVisibilityEffects(
    focusRequestToken: Long,
    isSheetVisible: Boolean,
    isRecording: Boolean,
    isDismissing: Boolean,
    onSheetVisibleChange: (Boolean) -> Unit,
    onSheetEntrySettledChange: (Boolean) -> Unit,
) {
    LaunchedEffect(focusRequestToken) {
        withFrameNanos { }
        onSheetVisibleChange(true)
    }

    LaunchedEffect(isSheetVisible, isRecording, isDismissing) {
        if (!isSheetVisible || isDismissing || isRecording) {
            onSheetEntrySettledChange(false)
            return@LaunchedEffect
        }
        onSheetEntrySettledChange(false)
        delay(INPUT_SHEET_ENTRY_SETTLE_DELAY_MILLIS)
        if (!isDismissing && !isRecording && isSheetVisible) {
            onSheetEntrySettledChange(true)
        }
    }

    LaunchedEffect(isSheetVisible, isDismissing) {
        if (!isSheetVisible || isDismissing) {
            onSheetEntrySettledChange(false)
        }
    }

    LaunchedEffect(isSheetVisible, isRecording, isDismissing) {
        if (!isSheetVisible || isDismissing) return@LaunchedEffect
        if (isRecording) {
            return@LaunchedEffect
        }
    }
}

@Composable
internal fun InputSheetFocusRequestEffects(state: InputSheetFocusRequestState) {
    var lastHandledFocusRequestToken by remember { mutableLongStateOf(Long.MIN_VALUE) }

    LaunchedEffect(
        state.isSheetVisible,
        state.presentationState,
        state.isRecording,
        state.isDismissing,
        state.keyboardController,
    ) {
        if (!state.isSheetVisible) return@LaunchedEffect
        when {
            state.isDismissing -> {
                releaseEditorFocusAndKeyboard(
                    keyboardController = state.keyboardController,
                    focusParkingRequester = state.focusParkingRequester,
                )
            }

            state.presentationState.shouldReleaseEditorFocus() -> {
                releaseEditorFocusAndKeyboard(
                    keyboardController = state.keyboardController,
                    focusParkingRequester = state.focusParkingRequester,
                )
            }

            state.isRecording -> state.keyboardController?.hide()
        }
    }

    LaunchedEffect(
        state.isSheetVisible,
        state.isSheetEntrySettled,
        state.presentationState,
        state.isRecording,
        state.isDismissing,
        state.focusRequestToken,
    ) {
        if (
            !shouldRequestInputSheetEditorFocus(
                isSheetVisible = state.isSheetVisible,
                isSheetEntrySettled = state.isSheetEntrySettled,
                presentationState = state.presentationState,
                isRecording = state.isRecording,
                isDismissing = state.isDismissing,
                focusRequestToken = state.focusRequestToken,
                lastHandledFocusRequestToken = lastHandledFocusRequestToken,
            )
        ) {
            return@LaunchedEffect
        }
        requestEditorFocusAndKeyboard(
            focusRequester = state.focusRequester,
            keyboardController = state.keyboardController,
        )
        lastHandledFocusRequestToken = state.focusRequestToken
    }
}

internal fun shouldRequestInputSheetEditorFocus(
    isSheetVisible: Boolean,
    isSheetEntrySettled: Boolean,
    presentationState: InputSheetPresentationState,
    isRecording: Boolean,
    isDismissing: Boolean,
    focusRequestToken: Long,
    lastHandledFocusRequestToken: Long,
): Boolean =
    isSheetVisible &&
        isSheetEntrySettled &&
        presentationState.prefersEditorFocus() &&
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
