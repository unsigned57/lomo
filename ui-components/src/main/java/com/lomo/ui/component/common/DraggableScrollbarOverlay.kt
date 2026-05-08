package com.lomo.ui.component.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.util.AppHapticFeedback
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
    isScrollInProgress: Boolean,
    recentlyScrolled: Boolean,
    thumbFraction: Float,
    thumbExtentPx: Float,
    onThumbFractionChanged: (Float) -> Unit,
    onDragStateChanged: (Boolean) -> Unit,
) {
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var draggedThumbOffsetPx by remember { mutableFloatStateOf(0f) }
    val visualState =
        resolveScrollbarThumbVisualState(
            isThumbDragged = isThumbDragged,
            isScrollInProgress = isScrollInProgress,
            recentlyScrolled = recentlyScrolled,
        )
    val thumbWidthPx = rememberAnimatedThumbWidthPx(isThumbDragged = isThumbDragged)
    val thumbColor = rememberDraggableScrollbarThumbColor(visualState = visualState)
    val haptics = LocalAppHapticFeedback.current

    AnimatedVisibility(
        visible = visible,
        modifier =
            Modifier
                .align(Alignment.CenterEnd)
                .padding(
                    top = DraggableScrollbarTrackPadding,
                    bottom = DraggableScrollbarTrackPadding,
                    end = DraggableScrollbarEndPadding,
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
        val animatedSettledOffsetPx by animateFloatAsState(
            targetValue = thumbMetrics.thumbOffsetPx,
            animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioNoBouncy),
            label = "ScrollbarThumbOffset",
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
                settledThumbOffsetPx = animatedSettledOffsetPx,
            )
        Box(
            modifier =
                Modifier.draggableScrollbarTrackModifier(
                    onTrackHeightChanged = { trackHeightPx = it },
                ),
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
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
            if (trackHeightPx > 0f && thumbMetrics.thumbExtentPx > 0f) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .draggableScrollbarThumbDragModifier(
                                thumbOffsetPx = displayedThumbOffsetPx,
                                trackHeightPx = trackHeightPx,
                                thumbExtentPx = thumbMetrics.thumbExtentPx,
                                onDraggedThumbOffsetChanged = { draggedThumbOffsetPx = it },
                                onThumbFractionChanged = onThumbFractionChanged,
                                onDragStateChanged = onDragStateChanged,
                                haptics = haptics,
                            ),
                )
            }
        }
    }
}

@Composable
private fun Modifier.draggableScrollbarTrackModifier(
    onTrackHeightChanged: (Float) -> Unit,
): Modifier =
    this
        .fillMaxHeight()
        .width(DraggableScrollbarTouchTargetWidth)
        .systemGestureExclusion()
        .onSizeChanged { size -> onTrackHeightChanged(size.height.toFloat()) }

@Composable
private fun Modifier.draggableScrollbarThumbDragModifier(
    thumbOffsetPx: Float,
    trackHeightPx: Float,
    thumbExtentPx: Float,
    onDraggedThumbOffsetChanged: (Float) -> Unit,
    onThumbFractionChanged: (Float) -> Unit,
    onDragStateChanged: (Boolean) -> Unit,
    haptics: AppHapticFeedback,
): Modifier {
    val thumbHeight = with(LocalDensity.current) { thumbExtentPx.toDp() }
    val thumbOffsetRef = rememberUpdatedState(thumbOffsetPx)
    val trackHeightRef = rememberUpdatedState(trackHeightPx)
    val thumbExtentRef = rememberUpdatedState(thumbExtentPx)
    val onDraggedThumbOffsetChangedRef = rememberUpdatedState(onDraggedThumbOffsetChanged)
    val onThumbFractionChangedRef = rememberUpdatedState(onThumbFractionChanged)
    val onDragStateChangedRef = rememberUpdatedState(onDragStateChanged)
    val hapticsRef = rememberUpdatedState(haptics)
    return this
        .offset { IntOffset(x = 0, y = thumbOffsetRef.value.roundToInt()) }
        .width(DraggableScrollbarTouchTargetWidth)
        .height(thumbHeight)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val trackPx = trackHeightRef.value
                val thumbPx = thumbExtentRef.value
                if (trackPx <= 0f) return@awaitEachGesture
                var currentOffset =
                    resolveThumbDragStartOffsetFromPress(
                        pressY = thumbOffsetRef.value + down.position.y,
                        currentThumbOffsetPx = thumbOffsetRef.value,
                        thumbExtentPx = thumbPx,
                        trackHeightPx = trackPx,
                    ) ?: return@awaitEachGesture
                down.consume()
                hapticsRef.value.longPress()
                onDragStateChangedRef.value(true)
                onDraggedThumbOffsetChangedRef.value(currentOffset)
                onThumbFractionChangedRef.value(
                    mapDraggedThumbOffsetToFraction(
                        draggedThumbOffsetPx = currentOffset,
                        trackExtentPx = trackPx,
                        thumbExtentPx = thumbPx,
                    ),
                )
                try {
                    var keepDragging = true
                    while (keepDragging) {
                        val event = awaitPointerEvent()
                        val change: PointerInputChange? =
                            event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            keepDragging = false
                        } else if (change.pressed) {
                            val dy = change.positionChange().y
                            if (dy != 0f) {
                                val activeTrack = trackHeightRef.value
                                val activeThumb = thumbExtentRef.value
                                currentOffset =
                                    resolveDraggedThumbOffsetPx(
                                        currentOffsetPx = currentOffset,
                                        dragAmount = dy,
                                        trackHeightPx = activeTrack,
                                        thumbExtentPx = activeThumb,
                                    )
                                onDraggedThumbOffsetChangedRef.value(currentOffset)
                                onThumbFractionChangedRef.value(
                                    mapDraggedThumbOffsetToFraction(
                                        draggedThumbOffsetPx = currentOffset,
                                        trackExtentPx = activeTrack,
                                        thumbExtentPx = activeThumb,
                                    ),
                                )
                            }
                        } else {
                            keepDragging = false
                        }
                        change?.consume()
                    }
                } finally {
                    onDragStateChangedRef.value(false)
                }
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
private fun rememberDraggableScrollbarThumbColor(visualState: ScrollbarThumbVisualState): Color {
    val animatedAlpha by animateFloatAsState(
        targetValue = resolveScrollbarThumbAlpha(visualState),
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "ScrollbarThumbAlpha",
    )
    return MaterialTheme.colorScheme.onSurface.copy(alpha = animatedAlpha)
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
