package com.lomo.ui.component.input

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.SoftwareKeyboardController

internal data class InputSheetLifecycleState(
    val sessionState: InputSheetSessionState,
    val sheetState: InputSheetState,
    val inputText: String,
    /** Preview hides the source text, so it withdraws the caret and keyboard. */
    val editorAcceptsFocus: Boolean,
    val focusRequester: FocusRequester,
    val focusParkingRequester: FocusRequester,
    val focusRequestToken: Long,
    val keyboardController: SoftwareKeyboardController?,
)

internal data class InputSheetFocusRequestState(
    val sessionState: InputSheetSessionState,
    /** Preview hides the source text, so it withdraws the caret and keyboard. */
    val editorAcceptsFocus: Boolean,
    val isRecording: Boolean,
    val focusRequester: FocusRequester,
    val focusParkingRequester: FocusRequester,
    val focusRequestToken: Long,
    val keyboardController: SoftwareKeyboardController?,
)
