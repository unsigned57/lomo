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

internal data class MemoTextSelectionEndpoint(
    val blockKey: Any,
    val offset: Int,
)

/**
 * Selection state shared by every paragraph inside a memo card's free-copy scope.
 *
 * The selection is described by two endpoints — anchor (where the long-press
 * started) and focus (where the drag handle currently sits). Endpoints may live
 * in different blocks; selection direction is resolved against the registered
 * block order so that the user-visible selection always reads forward.
 */
internal data class MemoMultiParagraphSelection(
    val anchor: MemoTextSelectionEndpoint?,
    val focus: MemoTextSelectionEndpoint?,
) {
    fun hasSelection(blockOrder: List<Any>): Boolean {
        val resolved = resolveForwardEndpoints(blockOrder) ?: return false
        val (start, end) = resolved
        if (start.blockKey != end.blockKey) return true
        return start.offset != end.offset
    }

    fun selectedRangeForBlock(
        blockKey: Any,
        blockTextLength: Int,
        blockOrder: List<Any>,
    ): IntRange? {
        val (start, end) = resolveForwardEndpoints(blockOrder) ?: return null
        return if (start.blockKey == end.blockKey) {
            resolveSingleBlockRange(blockKey, start, end, blockTextLength)
        } else {
            resolveMultiBlockRange(blockKey, start, end, blockTextLength, blockOrder)
        }
    }

    private fun resolveSingleBlockRange(
        blockKey: Any,
        start: MemoTextSelectionEndpoint,
        end: MemoTextSelectionEndpoint,
        blockTextLength: Int,
    ): IntRange? {
        if (blockKey != start.blockKey) return null
        val lo = start.offset.coerceIn(0, blockTextLength)
        val hi = end.offset.coerceIn(0, blockTextLength)
        return if (lo == hi) null else lo until hi
    }

    private fun resolveMultiBlockRange(
        blockKey: Any,
        start: MemoTextSelectionEndpoint,
        end: MemoTextSelectionEndpoint,
        blockTextLength: Int,
        blockOrder: List<Any>,
    ): IntRange? {
        val startIndex = blockOrder.indexOf(start.blockKey)
        val endIndex = blockOrder.indexOf(end.blockKey)
        val targetIndex = blockOrder.indexOf(blockKey)
        if (startIndex < 0 || endIndex < 0 || targetIndex < 0) return null
        return when {
            targetIndex < startIndex || targetIndex > endIndex -> null
            targetIndex == startIndex -> {
                val lo = start.offset.coerceIn(0, blockTextLength)
                if (lo >= blockTextLength) null else lo until blockTextLength
            }
            targetIndex == endIndex -> {
                val hi = end.offset.coerceIn(0, blockTextLength)
                if (hi <= 0) null else 0 until hi
            }
            else -> 0 until blockTextLength
        }
    }

    fun selectedText(blocksInOrder: List<Pair<Any, String>>): String {
        val keysInOrder = blocksInOrder.map { it.first }
        if (!hasSelection(keysInOrder)) return ""
        return blocksInOrder
            .mapNotNull { (key, text) ->
                val range =
                    selectedRangeForBlock(
                        blockKey = key,
                        blockTextLength = text.length,
                        blockOrder = keysInOrder,
                    ) ?: return@mapNotNull null
                text.substring(range.first, range.last + 1)
            }
            .joinToString(separator = "\n")
    }

    private fun resolveForwardEndpoints(
        blockOrder: List<Any>,
    ): Pair<MemoTextSelectionEndpoint, MemoTextSelectionEndpoint>? {
        val a = anchor ?: return null
        val f = focus ?: return null
        if (a.blockKey == f.blockKey) {
            return if (a.offset <= f.offset) a to f else f to a
        }
        val anchorIndex = blockOrder.indexOf(a.blockKey)
        val focusIndex = blockOrder.indexOf(f.blockKey)
        if (anchorIndex < 0 || focusIndex < 0) return null
        return if (anchorIndex <= focusIndex) a to f else f to a
    }

    companion object {
        val None = MemoMultiParagraphSelection(anchor = null, focus = null)
    }
}

internal data class MemoTextLinkRange(
    val start: Int,
    val end: Int,
    val url: String,
) {
    fun contains(offset: Int): Boolean = offset in start until end
}

internal fun MemoTextSelectionState.selectedAnchorOffset(): Int? = selectedRange?.first

internal fun MemoTextSelectionState.selectedFocusOffset(): Int? = selectedRange?.let { it.last + 1 }
