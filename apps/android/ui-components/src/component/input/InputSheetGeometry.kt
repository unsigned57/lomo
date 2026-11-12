package com.lomo.ui.component.input

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlin.math.roundToInt

/**
 * Where one sheet form sits in the window.
 *
 * [topGap] is window space kept clear above the surface and [contentTopInset] is space inside the
 * surface above its content. A full-bleed long-form surface trades the first for the second, so its
 * content edge stays on the status-bar line while the background grows underneath it.
 */
internal data class InputSheetFormGeometry(
    val width: Dp,
    val cornerRadius: Dp,
    val topGap: Dp,
    val contentTopInset: Dp,
)

/** Both forms for one window. [at] interpolates every property on the single expansion fraction. */
internal data class InputSheetGeometry(val compact: InputSheetFormGeometry, val expanded: InputSheetFormGeometry) {
    /** Spring samples may leave 0..1; geometry never extrapolates past either form. */
    fun at(expansion: Float): InputSheetFormGeometry {
        val fraction = expansion.coerceIn(0f, 1f)
        return InputSheetFormGeometry(
            width = lerp(compact.width, expanded.width, fraction),
            cornerRadius = lerp(compact.cornerRadius, expanded.cornerRadius, fraction),
            topGap = lerp(compact.topGap, expanded.topGap, fraction),
            contentTopInset = lerp(compact.contentTopInset, expanded.contentTopInset, fraction),
        )
    }
}

/**
 * A sheet that already spans the window grows into a full-bleed long-form surface. On a wider window
 * the sheet is a bottom-sheet-width card and keeps that one rounded shape in both forms: the morph is
 * purely vertical, so text never reflows mid-motion and the measure stays readable.
 */
internal fun resolveInputSheetGeometry(containerWidth: Dp, statusBarTop: Dp): InputSheetGeometry {
    val width = minOf(containerWidth, InputSheetTokens.MaxWidth)
    if (width < containerWidth) {
        val card =
            InputSheetFormGeometry(
                width = width,
                cornerRadius = InputSheetTokens.CornerRadius,
                topGap = statusBarTop + InputSheetTokens.CardTopMargin,
                contentTopInset = 0.dp,
            )
        return InputSheetGeometry(compact = card, expanded = card)
    }
    return InputSheetGeometry(
        compact =
        InputSheetFormGeometry(
            width = width,
            cornerRadius = InputSheetTokens.CornerRadius,
            topGap = statusBarTop,
            contentTopInset = 0.dp,
        ),
        expanded =
        InputSheetFormGeometry(
            width = width,
            cornerRadius = 0.dp,
            topGap = 0.dp,
            contentTopInset = statusBarTop,
        ),
    )
}

/** Height constraints for the editor canvas; the text field measures its content inside them. */
internal data class InputEditorCanvasHeightRange(val minPx: Int, val maxPx: Int)

/**
 * The compact canvas wraps its text between the 3- and 6-line heights. Once the morph starts, the
 * canvas travels from the text's own compact height to the available height along one straight path,
 * so the surface edge moves uniformly instead of stalling while short text catches up.
 *
 * [contentPx] is the text's natural height including the canvas padding; [availablePx] is what the
 * panel leaves for the canvas. Nothing ever exceeds the available space.
 */
internal fun resolveInputEditorCanvasHeightRange(
    expansion: Float,
    compactMinPx: Int,
    compactMaxPx: Int,
    contentPx: Int,
    availablePx: Int,
): InputEditorCanvasHeightRange {
    val fill = availablePx.coerceAtLeast(0)
    val compactMin = compactMinPx.coerceIn(0, fill)
    val compactMax = compactMaxPx.coerceIn(compactMin, fill)
    val fraction = expansion.coerceIn(0f, 1f)
    if (fraction == 0f) return InputEditorCanvasHeightRange(compactMin, compactMax)
    val compactHeight = contentPx.coerceIn(compactMin, compactMax)
    val height = (compactHeight + (fill - compactHeight) * fraction).roundToInt().coerceIn(compactHeight, fill)
    return InputEditorCanvasHeightRange(height, height)
}

/** Distance the canvas travels between the forms: the drag gesture's full span. */
internal fun resolveInputEditorCanvasTravelPx(
    compactMinPx: Int,
    compactMaxPx: Int,
    contentPx: Int,
    availablePx: Int,
): Int {
    val compact = resolveInputEditorCanvasHeightRange(0f, compactMinPx, compactMaxPx, contentPx, availablePx)
    return availablePx.coerceAtLeast(0) - contentPx.coerceIn(compact.minPx, compact.maxPx)
}

/**
 * Where a released drag settles: a fling beyond [velocityThresholdPxPerSecond] follows its direction
 * (upward expands), a slow release settles to the nearer form. Returns true for the long-form.
 */
internal fun resolveInputSheetSettleTarget(
    fraction: Float,
    velocityPxPerSecond: Float,
    velocityThresholdPxPerSecond: Float,
): Boolean = when {
    velocityPxPerSecond <= -velocityThresholdPxPerSecond -> true
    velocityPxPerSecond >= velocityThresholdPxPerSecond -> false
    else -> fraction >= HALFWAY
}

private const val HALFWAY = 0.5f

/** Width of the header's centered handle slot: full on the compact card, gone in the long form. */
internal fun resolveInputSheetHandleSlotWidthPx(fraction: Float, slotPx: Float): Int =
    (slotPx * (1f - fraction.coerceIn(0f, 1f))).roundToInt()

/** Space above the header row, interpolated between the compact and long-form insets. */
internal fun resolveInputSheetHeaderTopInsetPx(fraction: Float, compactPx: Float, expandedPx: Float): Int =
    (compactPx + (expandedPx - compactPx) * fraction.coerceIn(0f, 1f)).roundToInt()
