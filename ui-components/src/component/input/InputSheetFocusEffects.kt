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
    isSheetVisible: Boolean,
    isRecording: Boolean,
    isDismissing: Boolean,
    onSheetVisibleChange: (Boolean) -> Unit,
    onSheetEntrySettledChange: (Boolean) -> Unit,
) {
    LaunchedEffect(Unit) {
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
internal fun InputSheetFocusRequestEffects(
    isSheetVisible: Boolean,
    isSheetEntrySettled: Boolean,
    presentationState: InputSheetPresentationState,
    isRecording: Boolean,
    isDismissing: Boolean,
    focusRequester: FocusRequester,
    focusParkingRequester: FocusRequester,
    focusRequestToken: Long,
    keyboardController: SoftwareKeyboardController?,
) {
    var lastHandledFocusRequestToken by remember { mutableLongStateOf(Long.MIN_VALUE) }

    LaunchedEffect(isSheetVisible, presentationState, isRecording, isDismissing, keyboardController) {
        if (!isSheetVisible) return@LaunchedEffect
        when {
            isDismissing -> {
                releaseEditorFocusAndKeyboard(
                    keyboardController = keyboardController,
                    focusParkingRequester = focusParkingRequester,
                )
            }

            presentationState.shouldReleaseEditorFocus() -> {
                releaseEditorFocusAndKeyboard(
                    keyboardController = keyboardController,
                    focusParkingRequester = focusParkingRequester,
                )
            }

            isRecording -> keyboardController?.hide()
        }
    }

    LaunchedEffect(
        isSheetVisible,
        isSheetEntrySettled,
        presentationState,
        isRecording,
        isDismissing,
        focusRequestToken,
    ) {
        if (
            !shouldRequestInputSheetEditorFocus(
                isSheetVisible = isSheetVisible,
                isSheetEntrySettled = isSheetEntrySettled,
                presentationState = presentationState,
                isRecording = isRecording,
                isDismissing = isDismissing,
                focusRequestToken = focusRequestToken,
                lastHandledFocusRequestToken = lastHandledFocusRequestToken,
            )
        ) {
            return@LaunchedEffect
        }
        requestEditorFocusAndKeyboard(
            focusRequester = focusRequester,
            keyboardController = keyboardController,
        )
        lastHandledFocusRequestToken = focusRequestToken
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
