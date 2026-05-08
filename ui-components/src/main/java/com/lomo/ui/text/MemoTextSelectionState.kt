package com.lomo.ui.text

internal data class MemoTextSelectionState(
    val anchorOffset: Int?,
    val focusOffset: Int?,
) {
    val hasSelection: Boolean
        get() = selectedRange != null

    val isCollapsed: Boolean
        get() = anchorOffset != null && anchorOffset == focusOffset

    val selectedRange: IntRange?
        get() {
            val anchor = anchorOffset ?: return null
            val focus = focusOffset ?: return null
            if (anchor == focus) return null
            return if (anchor < focus) {
                anchor until focus
            } else {
                focus until anchor
            }
        }

    fun selectedText(text: String): String {
        val range = selectedRange ?: return ""
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        return text.substring(start, end)
    }

    fun updateFocus(offset: Int): MemoTextSelectionState = copy(focusOffset = offset)

    fun clear(): MemoTextSelectionState = None

    companion object {
        val None = MemoTextSelectionState(anchorOffset = null, focusOffset = null)
    }
}

internal data class MemoTextLinkRange(
    val start: Int,
    val end: Int,
    val url: String,
) {
    fun contains(offset: Int): Boolean = offset in start until end
}

internal fun shouldActivateMemoTextLink(
    selectionState: MemoTextSelectionState,
    offset: Int,
    links: List<MemoTextLinkRange>,
): Boolean =
    !selectionState.hasSelection && links.any { link -> link.contains(offset) }

internal fun MemoTextSelectionState.selectedAnchorOffset(): Int? = selectedRange?.first

internal fun MemoTextSelectionState.selectedFocusOffset(): Int? = selectedRange?.let { it.last + 1 }
