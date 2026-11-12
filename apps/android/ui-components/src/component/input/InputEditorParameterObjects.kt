package com.lomo.ui.component.input

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.ui.util.AppHapticFeedback
import kotlinx.collections.immutable.ImmutableList

internal data class InputEditorPanelState(
    val motion: InputSheetMotion,
    val inputValue: TextFieldValue,
    val hintText: String,
    val availableTags: ImmutableList<String>,
    val showTagSelector: Boolean,
    val focusRequester: FocusRequester,
    val surface: InputEditorSurfaceState,
    val slots: InputSheetSlots,
)

internal data class InputEditorPanelCallbacks(
    val onTextChange: (TextFieldValue) -> Unit,
    val onTagSelected: (String) -> Unit,
    val onExpandedChange: (Boolean) -> Unit,
    val onDisplayModeChange: (InputEditorDisplayMode) -> Unit,
    val onEditorCommand: (InputEditorCommand) -> Unit,
    val onToolbarOrderChanged: (List<InputToolbarActionId>) -> Unit,
    val onSubmit: () -> Unit,
)

internal data class InputEditorTextFieldState(
    val showsPlaceholder: Boolean,
    val inputValue: TextFieldValue,
    val hintText: String,
    val focusRequester: FocusRequester,
    val textStyle: TextStyle,
    val placeholderTextStyle: TextStyle,
    val benchmarkEditorTag: String?,
    val hiddenTextRanges: ImmutableList<androidx.compose.ui.text.TextRange>,
    val inputState: androidx.compose.foundation.text.input.TextFieldState,
    val inputTransformation: androidx.compose.foundation.text.input.InputTransformation?,
)

internal data class InputEditorToolbarState(
    val isSubmitEnabled: Boolean,
    val enabled: Boolean,
    val tools: ImmutableList<InputToolbarTool>,
    val benchmarkSubmitTag: String?,
    val haptic: AppHapticFeedback,
)

internal data class InputEditorToolbarCallbacks(
    val onEditorCommand: (InputEditorCommand) -> Unit,
    val onToolbarOrderChanged: (List<InputToolbarActionId>) -> Unit,
    val onSubmit: () -> Unit,
)
