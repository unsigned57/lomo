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
