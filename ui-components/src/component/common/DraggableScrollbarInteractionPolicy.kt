package com.lomo.ui.component.common

internal fun resolveScrollbarThumbVisualState(
    isThumbDragged: Boolean,
    isScrollInProgress: Boolean,
    recentlyScrolled: Boolean,
): ScrollbarThumbVisualState =
    when {
        isThumbDragged -> ScrollbarThumbVisualState.Drag
        isScrollInProgress || recentlyScrolled -> ScrollbarThumbVisualState.Active
        else -> ScrollbarThumbVisualState.Idle
    }

internal fun resolveScrollbarThumbAlpha(state: ScrollbarThumbVisualState): Float =
    when (state) {
        ScrollbarThumbVisualState.Idle -> DraggableScrollbarTokens.IdleAlpha
        ScrollbarThumbVisualState.Active -> DraggableScrollbarTokens.ActiveAlpha
        ScrollbarThumbVisualState.Drag -> DraggableScrollbarTokens.DragAlpha
    }

/** True iff [pointerXPx] falls inside the end-aligned scrollbar touch zone of a canvas of width [canvasWidthPx]. */
internal fun isPointInScrollbarTouchZone(
    pointerXPx: Float,
    canvasWidthPx: Float,
    touchTargetWidthPx: Float,
): Boolean {
    if (canvasWidthPx <= 0f) return false
    if (pointerXPx < 0f) return false
    val zoneStart = (canvasWidthPx - touchTargetWidthPx).coerceAtLeast(0f)
    return pointerXPx >= zoneStart && pointerXPx <= canvasWidthPx
}

internal fun resolveDisplayedThumbOffsetPx(
    isThumbDragged: Boolean,
    draggedThumbOffsetPx: Float,
    settledThumbOffsetPx: Float,
): Float = if (isThumbDragged) draggedThumbOffsetPx else settledThumbOffsetPx

internal fun shouldSyncDraggedThumbOffsetFromExternal(
    visible: Boolean,
    isThumbDragged: Boolean,
): Boolean = !visible || !isThumbDragged

internal fun resolveThumbDragStartOffsetFromPress(
    pressY: Float,
    currentThumbOffsetPx: Float,
    thumbExtentPx: Float,
    trackHeightPx: Float,
): Float? {
    if (trackHeightPx <= 0f || thumbExtentPx <= 0f) return null
    val resolvedThumbExtent = thumbExtentPx.coerceIn(0f, trackHeightPx)
    val maxOffset = (trackHeightPx - resolvedThumbExtent).coerceAtLeast(0f)
    val thumbTop = currentThumbOffsetPx.coerceIn(0f, maxOffset)
    val thumbBottom = thumbTop + resolvedThumbExtent
    if (pressY in thumbTop..thumbBottom) {
        return thumbTop
    }
    return null
}
