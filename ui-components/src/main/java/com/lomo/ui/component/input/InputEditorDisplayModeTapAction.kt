package com.lomo.ui.component.input

internal sealed interface InputEditorDisplayModeTapAction {
    data object Collapse : InputEditorDisplayModeTapAction

    data class ChangeMode(
        val mode: InputEditorDisplayMode,
    ) : InputEditorDisplayModeTapAction
}

internal fun resolveInputEditorDisplayModeTapAction(
    currentMode: InputEditorDisplayMode,
    tappedMode: InputEditorDisplayMode,
): InputEditorDisplayModeTapAction =
    if (currentMode == tappedMode) {
        InputEditorDisplayModeTapAction.Collapse
    } else {
        InputEditorDisplayModeTapAction.ChangeMode(tappedMode)
    }
