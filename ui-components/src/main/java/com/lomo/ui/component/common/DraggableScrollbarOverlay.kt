package com.lomo.ui.component.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.lomo.ui.theme.MotionTokens
import kotlinx.coroutines.delay

@Composable
internal fun rememberDraggableScrollbarInteractionState(
    canScroll: Boolean,
    isScrollInProgress: Boolean,
): DraggableScrollbarInteractionState {
    val state = remember { DraggableScrollbarInteractionState() }
    LaunchedEffect(canScroll, isScrollInProgress, state.isThumbDragged) {
        if (!canScroll) {
            state.recentlyScrolled = false
            return@LaunchedEffect
        }
        if (isScrollInProgress || state.isThumbDragged) {
            state.recentlyScrolled = true
            return@LaunchedEffect
        }
        if (state.recentlyScrolled) {
            delay(DRAGGABLE_SCROLLBAR_FADE_OUT_DELAY_MS)
            state.recentlyScrolled = false
        }
    }
    return state
}

@Stable
internal class DraggableScrollbarInteractionState {
    var isThumbDragged by mutableStateOf(false)
    var recentlyScrolled by mutableStateOf(false)
}

@Composable
internal fun BoxScope.DraggableScrollbarOverlay(
    visible: Boolean,
    isThumbDragged: Boolean,
    thumbFraction: Float,
    thumbExtentPx: Float,
    onThumbFractionChanged: (Float) -> Unit,
    onDragStateChanged: (Boolean) -> Unit,
) {
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var draggedThumbOffsetPx by remember { mutableFloatStateOf(0f) }
    val thumbWidthPx = rememberAnimatedThumbWidthPx(isThumbDragged = isThumbDragged)
    val thumbColor = rememberDraggableScrollbarThumbColor(isThumbDragged = isThumbDragged)

    AnimatedVisibility(
        visible = visible,
        modifier =
            Modifier
                .align(Alignment.CenterEnd)
                .padding(
                    top = DraggableScrollbarTrackPadding,
                    bottom = DraggableScrollbarTrackPadding,
                    end = DraggableScrollbarTrackPadding,
                ),
        enter = draggableScrollbarFadeInTransition(),
        exit = draggableScrollbarFadeOutTransition(),
    ) {
        val thumbMetrics =
            resolveScrollbarThumbMetrics(
                trackExtentPx = trackHeightPx,
                thumbExtentPx = thumbExtentPx,
                scrollFraction = thumbFraction,
            )
        LaunchedEffect(thumbMetrics.thumbOffsetPx, visible, isThumbDragged) {
            if (shouldSyncDraggedThumbOffsetFromExternal(visible = visible, isThumbDragged = isThumbDragged)) {
                draggedThumbOffsetPx = thumbMetrics.thumbOffsetPx
            }
        }
        val displayedThumbOffsetPx =
            resolveDisplayedThumbOffsetPx(
                isThumbDragged = isThumbDragged,
                draggedThumbOffsetPx = draggedThumbOffsetPx,
                settledThumbOffsetPx = thumbMetrics.thumbOffsetPx,
            )
        Canvas(
            modifier =
                Modifier.draggableScrollbarCanvasModifier(
                    trackHeightPx = trackHeightPx,
                    thumbExtentPx = thumbExtentPx,
                    thumbMetrics = thumbMetrics,
                    draggedThumbOffsetPx = draggedThumbOffsetPx,
                    onTrackHeightChanged = { trackHeightPx = it },
                    onDraggedThumbOffsetChanged = { draggedThumbOffsetPx = it },
                    onThumbFractionChanged = onThumbFractionChanged,
                    onDragStateChanged = onDragStateChanged,
                ),
        ) {
            if (size.height <= 0f || size.width <= 0f) {
                return@Canvas
            }
            val cornerPx = thumbWidthPx / 2f
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(size.width - thumbWidthPx, displayedThumbOffsetPx),
                size = Size(thumbWidthPx, thumbMetrics.thumbExtentPx.coerceAtMost(size.height)),
                cornerRadius = CornerRadius(cornerPx, cornerPx),
            )
        }
    }
}

@Composable
private fun rememberAnimatedThumbWidthPx(isThumbDragged: Boolean): Float {
    val animatedThumbWidth by animateDpAsState(
        targetValue = if (isThumbDragged) DraggableScrollbarDragWidth else DraggableScrollbarIdleWidth,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "ScrollbarThumbWidth",
    )
    return with(LocalDensity.current) { animatedThumbWidth.toPx() }
}

@Composable
private fun rememberDraggableScrollbarThumbColor(isThumbDragged: Boolean) =
    if (isThumbDragged) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = DRAGGABLE_SCROLLBAR_DRAG_ALPHA)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = DRAGGABLE_SCROLLBAR_IDLE_ALPHA)
    }

private fun draggableScrollbarFadeInTransition() =
    fadeIn(
        animationSpec =
            tween(
                durationMillis = MotionTokens.DurationMedium1,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
    )

private fun draggableScrollbarFadeOutTransition() =
    fadeOut(
        animationSpec =
            tween(
                durationMillis = MotionTokens.DurationMedium1,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
    )

@Composable
private fun Modifier.draggableScrollbarCanvasModifier(
    trackHeightPx: Float,
    thumbExtentPx: Float,
    thumbMetrics: ScrollbarThumbMetrics,
    draggedThumbOffsetPx: Float,
    onTrackHeightChanged: (Float) -> Unit,
    onDraggedThumbOffsetChanged: (Float) -> Unit,
    onThumbFractionChanged: (Float) -> Unit,
    onDragStateChanged: (Boolean) -> Unit,
): Modifier {
    val touchTargetWidthDp = DraggableScrollbarTouchTargetWidth
    return this
        .fillMaxHeight()
        .width(touchTargetWidthDp)
        .onSizeChanged { size -> onTrackHeightChanged(size.height.toFloat()) }
        .pointerInput(trackHeightPx, thumbExtentPx, thumbMetrics.thumbOffsetPx, draggedThumbOffsetPx) {
            detectVerticalDragGestures(
                onDragStart = {
                    onDraggedThumbOffsetChanged(thumbMetrics.thumbOffsetPx)
                    onDragStateChanged(true)
                },
                onDragEnd = { onDragStateChanged(false) },
                onDragCancel = { onDragStateChanged(false) },
                onVerticalDrag = { change, dragAmount ->
                    if (trackHeightPx <= 0f) {
                        return@detectVerticalDragGestures
                    }
                    change.consume()
                    val updatedThumbOffset =
                        resolveDraggedThumbOffsetPx(
                            currentOffsetPx = draggedThumbOffsetPx,
                            dragAmount = dragAmount,
                            trackHeightPx = trackHeightPx,
                            thumbExtentPx = thumbExtentPx,
                        )
                    onDraggedThumbOffsetChanged(updatedThumbOffset)
                    onThumbFractionChanged(
                        mapDraggedThumbOffsetToFraction(
                            draggedThumbOffsetPx = updatedThumbOffset,
                            trackExtentPx = trackHeightPx,
                            thumbExtentPx = thumbExtentPx,
                        ),
                    )
                },
            )
        }
}

private fun resolveDraggedThumbOffsetPx(
    currentOffsetPx: Float,
    dragAmount: Float,
    trackHeightPx: Float,
    thumbExtentPx: Float,
): Float {
    val resolvedThumbExtent = thumbExtentPx.coerceIn(0f, trackHeightPx)
    val draggableExtent = (trackHeightPx - resolvedThumbExtent).coerceAtLeast(1f)
    return (currentOffsetPx + dragAmount).coerceIn(0f, draggableExtent)
}
