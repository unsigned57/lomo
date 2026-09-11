package com.lomo.ui.component.input

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.SoftwareKeyboardController
import kotlin.coroutines.cancellation.CancellationException

internal suspend fun releaseEditorFocusAndKeyboard(
    keyboardController: SoftwareKeyboardController?,
    focusParkingRequester: FocusRequester,
) {
    repeat(INPUT_SHEET_FOCUS_RELEASE_MAX_ATTEMPTS) {
        releaseEditorWindowFocus(focusParkingRequester = focusParkingRequester)
        keyboardController?.hide()
        withFrameNanos { }
    }
}

internal fun releaseEditorWindowFocus(focusParkingRequester: FocusRequester) {
    runCatching { focusParkingRequester.requestFocus() }
}

internal suspend fun requestEditorFocusAndKeyboard(
    focusRequester: FocusRequester,
    keyboardController: SoftwareKeyboardController?,
) {
    repeat(INPUT_SHEET_FOCUS_REQUEST_MAX_ATTEMPTS) {
        try {
            focusRequester.requestFocus()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // behavior-contract: silent-result-ok: editor focus is retried across frames; one miss is not a failure
        }
        withFrameNanos { }
        keyboardController?.show()
    }
}
