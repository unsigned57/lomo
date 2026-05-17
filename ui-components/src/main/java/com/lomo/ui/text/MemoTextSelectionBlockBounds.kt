package com.lomo.ui.text

import androidx.compose.ui.geometry.Offset

/**
 * Bounds of a registered selectable block expressed in the parent selection
 * scope's coordinate space. The hit tester uses these to map a scope-relative
 * drag position back to a single block so the selection scope can compute the
 * intra-block offset from the block's own [MemoTextLayout].
 */
internal data class MemoTextSelectionBlockBounds(
    val blockKey: Any,
    val topPx: Float,
    val bottomPx: Float,
    val leftPx: Float,
    val rightPx: Float,
)

/**
 * Picks the [MemoTextSelectionBlockBounds] that owns a scope-relative drag
 * position. A position inside a block's vertical band wins outright; otherwise
 * the block with the smallest vertical gap wins. Empty inputs return null.
 */
internal fun resolveMemoTextSelectionHitBlock(
    blocks: List<MemoTextSelectionBlockBounds>,
    scopePosition: Offset,
): MemoTextSelectionBlockBounds? {
    if (blocks.isEmpty()) return null
    blocks
        .firstOrNull { scopePosition.y >= it.topPx && scopePosition.y <= it.bottomPx }
        ?.let { return it }
    return blocks.minBy { block ->
        when {
            scopePosition.y < block.topPx -> block.topPx - scopePosition.y
            scopePosition.y > block.bottomPx -> scopePosition.y - block.bottomPx
            else -> 0f
        }
    }
}
