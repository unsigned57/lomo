package com.lomo.ui.component.input

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class InputEditorToggleIcon {
    Expand,
    Collapse,
}

internal data class InputEditorChromeTransitionState(
    val keepsHostMounted: Boolean,
    val isVisible: Boolean,
    val isInteractive: Boolean,
    val hiddenOffsetY: Dp,
)

internal data class InputEditorChromeState(
    val toggleIcon: InputEditorToggleIcon,
    val showsStandaloneModeBar: Boolean,
    val showsPlaceholder: Boolean,
    val displayModeBar: InputEditorChromeTransitionState,
    val formattingToolbar: InputEditorChromeTransitionState,
    val showsPreviewContent: Boolean,
) {
    val showsDisplayModeToggle: Boolean
        get() = displayModeBar.isVisible

    val showsFormattingToolbar: Boolean
        get() = formattingToolbar.isVisible
}

internal fun resolveInputEditorChromeState(
    isExpanded: Boolean,
    displayMode: InputEditorDisplayMode,
    inputText: String,
    hintText: String,
): InputEditorChromeState =
    resolveInputEditorChromeState(
        presentationState =
            resolveSettledInputSheetPresentationState(
                targetExpanded = isExpanded,
                targetDisplayMode =
                    if (isExpanded) {
                        displayMode
                    } else {
                        InputEditorDisplayMode.Edit
                    },
            ),
        inputText = inputText,
        hintText = hintText,
    )

internal fun resolveInputEditorChromeState(
    presentationState: InputSheetPresentationState,
    inputText: String,
    hintText: String,
): InputEditorChromeState =
    InputEditorChromeState(
        toggleIcon =
            if (presentationState.surfaceMotionStage().usesExpandedSurfaceForm()) {
                InputEditorToggleIcon.Collapse
            } else {
                InputEditorToggleIcon.Expand
            },
        showsStandaloneModeBar = false,
        showsPlaceholder =
            presentationState.showsEditorContent() &&
                presentationState.surfaceMotionStage() == InputSheetMotionStage.Compact &&
                inputText.isEmpty() &&
                hintText.isNotEmpty(),
        displayModeBar = resolveInputEditorDisplayModeBarTransitionState(presentationState),
        formattingToolbar = resolveInputEditorFormattingToolbarTransitionState(presentationState),
        showsPreviewContent = presentationState.showsPreviewLayer(),
    )

private val INPUT_EDITOR_DISPLAY_MODE_BAR_HIDDEN_OFFSET = DISPLAY_MODE_BAR_HIDDEN_OFFSET.dp
private val INPUT_EDITOR_FORMATTING_TOOLBAR_HIDDEN_OFFSET = FORMATTING_TOOLBAR_HIDDEN_OFFSET.dp

private const val DISPLAY_MODE_BAR_HIDDEN_OFFSET = -6
private const val FORMATTING_TOOLBAR_HIDDEN_OFFSET = 12

internal fun resolveInputEditorDisplayModeBarTransitionState(
    presentationState: InputSheetPresentationState,
): InputEditorChromeTransitionState =
    InputEditorChromeTransitionState(
        keepsHostMounted = presentationState != InputSheetPresentationState.CompactEdit,
        isVisible = presentationState.showsDisplayModeToggle(),
        isInteractive = presentationState.showsDisplayModeToggle(),
        hiddenOffsetY = INPUT_EDITOR_DISPLAY_MODE_BAR_HIDDEN_OFFSET,
    )

internal fun resolveInputEditorFormattingToolbarTransitionState(
    presentationState: InputSheetPresentationState,
): InputEditorChromeTransitionState =
    InputEditorChromeTransitionState(
        keepsHostMounted = true,
        isVisible = presentationState.showsFormattingToolbar(),
        isInteractive = presentationState.showsFormattingToolbar(),
        hiddenOffsetY = INPUT_EDITOR_FORMATTING_TOOLBAR_HIDDEN_OFFSET,
    )
