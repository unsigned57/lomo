package com.lomo.ui.component.common

internal fun resolveDisplayedThumbOffsetPx(
    isThumbDragged: Boolean,
    draggedThumbOffsetPx: Float,
    settledThumbOffsetPx: Float,
): Float = if (isThumbDragged) draggedThumbOffsetPx else settledThumbOffsetPx

internal fun shouldSyncDraggedThumbOffsetFromExternal(
    visible: Boolean,
    isThumbDragged: Boolean,
): Boolean = !visible || !isThumbDragged

internal fun resolveInitialThumbOffsetFromPress(
    pressY: Float,
    currentThumbOffsetPx: Float,
    thumbExtentPx: Float,
    trackHeightPx: Float,
): Float {
    if (trackHeightPx <= 0f) return 0f
    val resolvedThumbExtent = thumbExtentPx.coerceIn(0f, trackHeightPx)
    val thumbTop = currentThumbOffsetPx
    val thumbBottom = thumbTop + resolvedThumbExtent
    if (pressY in thumbTop..thumbBottom) {
        return currentThumbOffsetPx
    }
    val maxOffset = (trackHeightPx - resolvedThumbExtent).coerceAtLeast(0f)
    return (pressY - resolvedThumbExtent / 2f).coerceIn(0f, maxOffset)
}
