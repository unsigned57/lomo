package com.lomo.ui.component.input

/**
 * Interaction chrome for one committed sheet target.
 *
 * Interactivity and semantics follow the target immediately; how far the motion toward it has got is
 * read from the expansion and display progress at draw time, never stored here.
 */
internal data class InputEditorChrome(
    val isExpanded: Boolean,
    val displayMode: InputEditorDisplayMode,
    val showsPlaceholder: Boolean,
) {
    /** Preview needs the long form's height; the compact card has no preview toggle. */
    val previewToggleInteractive: Boolean
        get() = isExpanded

    /** Formatting, undo/redo and the caret only make sense while the source text is on screen. */
    val formattingInteractive: Boolean
        get() = displayMode == InputEditorDisplayMode.Edit

    val editorAcceptsFocus: Boolean
        get() = formattingInteractive
}

internal fun resolveInputEditorChrome(
    isExpanded: Boolean,
    displayMode: InputEditorDisplayMode,
    inputText: String,
    hintText: String,
): InputEditorChrome {
    val effectiveMode = if (isExpanded) displayMode else InputEditorDisplayMode.Edit
    return InputEditorChrome(
        isExpanded = isExpanded,
        displayMode = effectiveMode,
        showsPlaceholder =
        effectiveMode == InputEditorDisplayMode.Edit && inputText.isEmpty() && hintText.isNotEmpty(),
    )
}

/** Hint opacity: only empty, editable text shows it, and it fades out as the long form opens. */
internal fun InputEditorChrome.hintAlpha(expansion: Float): Float =
    if (showsPlaceholder) 1f - expansion.coerceIn(0f, 1f) else 0f
