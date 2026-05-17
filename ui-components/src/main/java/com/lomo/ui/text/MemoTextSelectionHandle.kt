package com.lomo.ui.text

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val MemoTextSelectionHandleTouchSize = 40.dp
internal val MemoTextSelectionHandleVisualSize = 22.dp

/**
 * Geometric description of the memo text selection handle.
 *
 * Each handle is a filled circle with one of its upper quadrants overlaid by a square so
 * that the corner abutting the selection caret is a hard right angle — the standard
 * Material text-handle anatomy used by the framework's [android.widget.TextView] handles
 * and Compose's `SelectionContainer`. The Anchor handle (LTR-leading) puts the shoulder
 * square on the upper-right; the Focus handle (LTR-trailing) mirrors it to the upper-left.
 *
 * Coordinates are local to the visual handle box of size `visualSize x visualSize`.
 */
internal data class MemoTextSelectionHandleGeometry(
    val circleCenter: Offset,
    val circleRadius: Float,
    val shoulderRect: Rect,
)

internal fun memoTextSelectionHandleGeometry(
    endpoint: MemoTextSelectionEndpointKind,
    visualSizePx: Float,
): MemoTextSelectionHandleGeometry {
    val radius = visualSizePx / 2f
    val shoulderRect =
        when (endpoint) {
            MemoTextSelectionEndpointKind.Anchor -> Rect(radius, 0f, visualSizePx, radius)
            MemoTextSelectionEndpointKind.Focus -> Rect(0f, 0f, radius, radius)
        }
    return MemoTextSelectionHandleGeometry(
        circleCenter = Offset(radius, radius),
        circleRadius = radius,
        shoulderRect = shoulderRect,
    )
}

/**
 * Top-left of the [MemoTextSelectionHandleTouchSize]×[MemoTextSelectionHandleTouchSize]
 * touch box so the inner visual circle's sharp shoulder corner lands on the caret point.
 *
 * The visual handle sits centered horizontally inside the larger touch box (an Android
 * convention so users can grab the handle even when their finger is slightly off): the
 * touch box's left edge is `(touchSize - visualSize) / 2` to the left of the visual box's
 * left edge. Horizontally, the visual box's outer (shoulder) corner aligns with the caret;
 * the touch box is shifted accordingly. Vertically, both boxes start at the caret's y so
 * the handle drops below the text line.
 */
internal fun resolveMemoTextSelectionHandleTopLeft(
    anchorPositionPx: Offset,
    touchSizePx: Float,
    visualSizePx: Float,
    endpoint: MemoTextSelectionEndpointKind,
): IntOffset {
    val horizontalPadding = (touchSizePx - visualSizePx) / 2f
    val touchLeft =
        when (endpoint) {
            // Visual's right edge sits at anchorX -> visualLeft = anchorX - visualSize.
            MemoTextSelectionEndpointKind.Anchor -> anchorPositionPx.x - visualSizePx - horizontalPadding
            // Visual's left edge sits at anchorX -> touchLeft = anchorX - horizontalPadding.
            MemoTextSelectionEndpointKind.Focus -> anchorPositionPx.x - horizontalPadding
        }
    return IntOffset(
        x = touchLeft.roundToInt(),
        y = anchorPositionPx.y.roundToInt(),
    )
}
