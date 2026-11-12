package com.lomo.ui.component.input

import androidx.compose.runtime.State
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.ui.util.AppHapticFeedback

/**
 * The sheet's two independent motion axes: [expansion] (compact ⇄ long form) and [displayProgress]
 * (0 = editing, 1 = preview) toward the committed [displayMode]. Readers sample them in layout and
 * draw lambdas, so motion never recomposes the editor.
 */
internal class InputSheetMotion(
    val expansion: InputSheetExpansionState,
    val displayMode: InputEditorDisplayMode,
    val displayProgress: State<Float>,
)

internal data class InputSheetBenchmarkTags(
    val root: String?,
    val editor: String?,
    val submit: String?,
    val expand: String?,
)

internal data class InputSheetContentParams(
    val state: InputSheetState,
    val callbacks: InputSheetCallbacks,
    val slots: InputSheetSlots,
    val sessionState: InputSheetSessionState,
    val motion: InputSheetMotion,
    val inputValue: TextFieldValue,
    val hintText: String,
    val focusRequester: FocusRequester,
    val focusParkingRequester: FocusRequester,
    val haptic: AppHapticFeedback,
    val dismissSheet: () -> Unit,
    val requestDismiss: () -> Unit,
    val handleTextChange: (TextFieldValue) -> Unit,
    val submitWithLock: (String, String, String) -> Unit,
    val benchmarkTags: InputSheetBenchmarkTags,
)

internal data class InputSheetBodyState(
    val isSheetVisible: Boolean,
    val showDiscardDialog: Boolean,
    val surface: InputEditorSurfaceState,
    val motion: InputSheetMotion,
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
    val onExpandedChange: (Boolean) -> Unit,
    val onDisplayModeChange: (InputEditorDisplayMode) -> Unit,
    val onEditorCommand: (InputEditorCommand) -> Unit,
    val onToolbarOrderChanged: (List<InputToolbarActionId>) -> Unit,
    val onSubmit: () -> Unit,
)
