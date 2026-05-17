package com.lomo.ui.text

/**
 * Outcome of a double-tap on a memo paragraph. Lets a pure decision function drive the
 * gesture handler in [MemoComposeParagraphText] so the "free-copy selection coexists with
 * quick-edit double-tap" contract is testable without spinning up a Compose host.
 */
internal sealed interface MemoParagraphDoubleTapOutcome {
    data class OpenEditor(val clearSelectionFirst: Boolean) : MemoParagraphDoubleTapOutcome
    data object Ignore : MemoParagraphDoubleTapOutcome
}

/**
 * Decides what a double-tap on a memo paragraph should do.
 *
 * Free-copy selection (long-press) and quick-edit double-tap are independent gestures: a
 * double-tap must always open the editor when a handler is wired, regardless of whether a
 * selection is currently active. When a selection IS active, the outcome instructs the
 * caller to clear it before opening the editor so the editor pane doesn't display on top
 * of dangling selection chrome (handles, toolbar, highlight).
 *
 * With no double-click handler wired ([hasDoubleClickHandler] = false), double-tap is a
 * no-op — this matches the legacy behavior where memo cards without an edit gesture
 * simply ignore the gesture.
 */
internal fun resolveMemoParagraphDoubleTapOutcome(
    hasDoubleClickHandler: Boolean,
    paragraphHasSelection: Boolean,
    scopeHasSelection: Boolean,
): MemoParagraphDoubleTapOutcome =
    if (!hasDoubleClickHandler) {
        MemoParagraphDoubleTapOutcome.Ignore
    } else {
        MemoParagraphDoubleTapOutcome.OpenEditor(
            clearSelectionFirst = paragraphHasSelection || scopeHasSelection,
        )
    }
