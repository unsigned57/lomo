package com.lomo.ui.component.input

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.ui.util.AppHapticFeedback

internal data class InputSheetContentParams(
    val state: InputSheetState,
    val callbacks: InputSheetCallbacks,
    val slots: InputSheetSlots,
    val sessionState: InputSheetSessionState,
    val presentationState: InputSheetPresentationState,
    val inputValue: TextFieldValue,
    val hintText: String,
    val focusRequester: FocusRequester,
    val focusParkingRequester: FocusRequester,
    val haptic: AppHapticFeedback,
    val dismissSheet: () -> Unit,
    val requestDismiss: () -> Unit,
    val handleTextChange: (TextFieldValue) -> Unit,
    val submitWithLock: (String, String, String) -> Unit,
    val benchmarkRootTag: String?,
    val benchmarkEditorTag: String?,
    val benchmarkSubmitTag: String?,
)

internal data class InputSheetBodyState(
    val isSheetVisible: Boolean,
    val showDiscardDialog: Boolean,
    val surface: InputEditorSurfaceState,
    val presentationState: InputSheetPresentationState,
    val inputValue: TextFieldValue,
    val hintText: String,
    val showTagSelector: Boolean,
    val focusRequester: FocusRequester,
    val focusParkingRequester: FocusRequester,
)

internal data class InputSheetBodyCallbacks(
    val onRequestDismiss: () -> Unit,
    val onDismissDiscardDialog: () -> Unit,
    val onConfirmDiscard: () -> Unit,
    val onTextChange: (TextFieldValue) -> Unit,
    val onTagSelected: (String) -> Unit,
    val onToggleExpanded: () -> Unit,
    val onDisplayModeChange: (InputEditorDisplayMode) -> Unit,
    val onEditorCommand: (InputEditorCommand) -> Unit,
    val onToolbarOrderChanged: (List<InputToolbarActionId>) -> Unit,
    val onSubmit: () -> Unit,
)
