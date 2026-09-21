package com.lomo.ui.component.input

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.ui.component.markdown.MarkdownRenderState
import com.lomo.ui.util.AppHapticFeedback
import kotlinx.collections.immutable.ImmutableList

internal data class InputEditorPanelState(
    val presentationState: InputSheetPresentationState,
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
    val onToggleExpanded: () -> Unit,
    val onDisplayModeChange: (InputEditorDisplayMode) -> Unit,
    val onEditorCommand: (InputEditorCommand) -> Unit,
    val onToolbarOrderChanged: (List<InputToolbarActionId>) -> Unit,
    val onSubmit: () -> Unit,
)

internal data class InputEditorBodyState(
    val isExpanded: Boolean,
    val chromeState: InputEditorChromeState,
    val inputValue: TextFieldValue,
    val previewState: MarkdownRenderState,
    val hintText: String,
    val focusRequester: FocusRequester,
    val inputTextStyle: TextStyle,
    val hintTextStyle: TextStyle,
)

internal data class InputEditorTextFieldState(
    val isExpanded: Boolean,
    val showsPlaceholder: Boolean,
    val inputValue: TextFieldValue,
    val hintText: String,
    val focusRequester: FocusRequester,
    val textStyle: TextStyle,
    val placeholderTextStyle: TextStyle,
    val benchmarkEditorTag: String?,
)

internal data class InputEditorToolbarState(
    val toggleIcon: InputEditorToggleIcon,
    val isExpanded: Boolean,
    val isSubmitEnabled: Boolean,
    val enabled: Boolean,
    val tools: ImmutableList<InputToolbarTool>,
    val benchmarkSubmitTag: String?,
    val haptic: AppHapticFeedback,
)

internal data class InputEditorToolbarCallbacks(
    val onToggleExpanded: () -> Unit,
    val onEditorCommand: (InputEditorCommand) -> Unit,
    val onToolbarOrderChanged: (List<InputToolbarActionId>) -> Unit,
    val onSubmit: () -> Unit,
)
