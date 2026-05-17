package com.lomo.ui.text

/**
 * Outcome of a tap on a memo paragraph. Lets a pure decision function drive the gesture
 * handler in [MemoComposeParagraphText] so the per-paragraph vs. scope-level selection
 * dismissal contract is testable without spinning up a Compose host.
 */
internal sealed interface MemoParagraphTapOutcome {
    data class OpenLink(val url: String) : MemoParagraphTapOutcome
    data object ClearSelection : MemoParagraphTapOutcome
    data object InvokeBodyClick : MemoParagraphTapOutcome
    data object Ignore : MemoParagraphTapOutcome
}

/**
 * Decides what a tap on a memo paragraph should do.
 *
 * The scope-level selection is shared across paragraphs, so the dismissal contract must
 * also work at scope granularity: a tap on **any** paragraph while a selection is active
 * cancels the selection — including paragraphs whose own range happens to be empty in a
 * cross-paragraph selection. Without this, expanded memos with multiple blocks trap the
 * user in a selection state they cannot tap-cancel out of, because each block's
 * `selectionState.hasSelection` looks at its own per-block range, not the shared scope.
 *
 * Links are deliberately suppressed while any selection is active so a tap on a linked
 * span resolves to "dismiss first" rather than navigation; the user can re-tap the link
 * after the selection is cleared.
 */
internal fun resolveMemoParagraphTapOutcome(
    link: MemoTextLinkRange?,
    paragraphHasSelection: Boolean,
    scopeHasSelection: Boolean,
): MemoParagraphTapOutcome =
    when {
        paragraphHasSelection || scopeHasSelection -> MemoParagraphTapOutcome.ClearSelection
        link != null -> MemoParagraphTapOutcome.OpenLink(link.url)
        else -> MemoParagraphTapOutcome.InvokeBodyClick
    }
