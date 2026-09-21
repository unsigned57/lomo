package com.lomo.ui.component.input

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.SoftwareKeyboardController

internal data class InputSheetLifecycleState(
    val sessionState: InputSheetSessionState,
    val sheetState: InputSheetState,
    val inputText: String,
    val presentationState: InputSheetPresentationState,
    val focusRequester: FocusRequester,
    val focusParkingRequester: FocusRequester,
    val focusRequestToken: Long,
    val keyboardController: SoftwareKeyboardController?,
)

internal data class InputSheetLifecycleCallbacks(
    val onCollapse: () -> Unit,
    val onConsumeBackPress: () -> Boolean,
    val onRequestDismiss: () -> Unit,
)

internal data class InputSheetFocusRequestState(
    val isSheetVisible: Boolean,
    val isSheetEntrySettled: Boolean,
    val presentationState: InputSheetPresentationState,
    val isRecording: Boolean,
    val isDismissing: Boolean,
    val focusRequester: FocusRequester,
    val focusParkingRequester: FocusRequester,
    val focusRequestToken: Long,
    val keyboardController: SoftwareKeyboardController?,
)
