package com.lomo.ui.component.input

internal enum class InputEditorToggleIcon {
    Expand,
    Collapse,
}

internal data class InputEditorChromeState(
    val toggleIcon: InputEditorToggleIcon,
    val showsStandaloneModeBar: Boolean,
    val showsPlaceholder: Boolean,
)

internal fun resolveInputEditorChromeState(
    isExpanded: Boolean,
    inputText: String,
    hintText: String,
): InputEditorChromeState =
    InputEditorChromeState(
        toggleIcon =
            if (isExpanded) {
                InputEditorToggleIcon.Collapse
            } else {
                InputEditorToggleIcon.Expand
            },
        showsStandaloneModeBar = false,
        showsPlaceholder = !isExpanded && inputText.isEmpty() && hintText.isNotEmpty(),
    )
