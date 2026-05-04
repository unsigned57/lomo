package com.lomo.ui.component.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal const val DRAGGABLE_SCROLLBAR_FADE_OUT_DELAY_MS = 1200L
internal val DraggableScrollbarTrackPadding = 2.dp
internal val DraggableScrollbarEndPadding = 8.dp
internal val DraggableScrollbarIdleWidth = 4.dp
internal val DraggableScrollbarDragWidth = 12.dp
internal val DraggableScrollbarTouchTargetWidth = 32.dp
internal val DraggableScrollbarThumbHeight = 36.dp
internal const val DRAGGABLE_SCROLLBAR_IDLE_ALPHA = 0.12f
internal const val DRAGGABLE_SCROLLBAR_ACTIVE_ALPHA = 0.25f
internal const val DRAGGABLE_SCROLLBAR_DRAG_ALPHA = 0.55f

internal fun shouldShowDraggableScrollbar(canScroll: Boolean): Boolean = canScroll

@Stable
internal data class ScrollbarThumbMetrics(
    val thumbExtentPx: Float,
    val thumbOffsetPx: Float,
)

@Stable
internal data class LazyListScrollTarget(
    val index: Int,
    val scrollOffsetPx: Int,
)

internal fun resolveScrollbarThumbMetrics(
    trackExtentPx: Float,
    thumbExtentPx: Float,
    scrollFraction: Float,
): ScrollbarThumbMetrics {
    val safeTrackExtent = trackExtentPx.coerceAtLeast(0f)
    val resolvedThumbExtent = thumbExtentPx.coerceIn(0f, safeTrackExtent)
    val maxThumbOffset = (safeTrackExtent - resolvedThumbExtent).coerceAtLeast(0f)
    return ScrollbarThumbMetrics(
        thumbExtentPx = resolvedThumbExtent,
        thumbOffsetPx = maxThumbOffset * scrollFraction.coerceIn(0f, 1f),
    )
}

internal fun mapThumbFractionToScrollOffset(
    thumbFraction: Float,
    maxScrollOffset: Int,
): Int = (thumbFraction.coerceIn(0f, 1f) * maxScrollOffset.coerceAtLeast(0)).roundToInt()

internal fun mapDraggedThumbOffsetToFraction(
    draggedThumbOffsetPx: Float,
    trackExtentPx: Float,
    thumbExtentPx: Float,
): Float {
    val safeTrackExtent = trackExtentPx.coerceAtLeast(0f)
    val resolvedThumbExtent = thumbExtentPx.coerceIn(0f, safeTrackExtent)
    val draggableExtent = (safeTrackExtent - resolvedThumbExtent).coerceAtLeast(1f)
    return (draggedThumbOffsetPx / draggableExtent).coerceIn(0f, 1f)
}

/** Forward mapping: thumb fraction = scrolledPx / maxScrollPx, so 1.0 means the list is at its real bottom. */
internal fun resolveLazyListThumbFractionByPixels(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    avgItemSizePx: Float,
    viewportSizePx: Int,
    totalItemsCount: Int,
): Float {
    if (avgItemSizePx <= 0f || viewportSizePx <= 0 || totalItemsCount <= 0) return 0f
    val totalContentPx = avgItemSizePx * totalItemsCount
    val maxScrollPx = (totalContentPx - viewportSizePx).coerceAtLeast(0f)
    if (maxScrollPx <= 0f) return 0f
    val safeFirst = firstVisibleItemIndex.coerceAtLeast(0)
    val safeOffset = firstVisibleItemScrollOffsetPx.coerceAtLeast(0)
    val scrolledPx = safeFirst * avgItemSizePx + safeOffset.toFloat()
    return (scrolledPx / maxScrollPx).coerceIn(0f, 1f)
}

/** Inverse of [resolveLazyListThumbFractionByPixels]; round-trips exactly so drag-to-bottom hits the real bottom. */
internal fun mapThumbFractionToLazyListPositionByPixels(
    fraction: Float,
    avgItemSizePx: Float,
    viewportSizePx: Int,
    totalItemsCount: Int,
): LazyListScrollTarget {
    if (avgItemSizePx <= 0f || totalItemsCount <= 0) {
        return LazyListScrollTarget(index = 0, scrollOffsetPx = 0)
    }
    val totalContentPx = avgItemSizePx * totalItemsCount
    val maxScrollPx =
        (totalContentPx - viewportSizePx.coerceAtLeast(0).toFloat()).coerceAtLeast(0f)
    val targetScrollPx = fraction.coerceIn(0f, 1f) * maxScrollPx
    val targetIndex =
        (targetScrollPx / avgItemSizePx).toInt()
            .coerceIn(0, (totalItemsCount - 1).coerceAtLeast(0))
    val intraOffsetPx = (targetScrollPx - targetIndex * avgItemSizePx).toInt().coerceAtLeast(0)
    return LazyListScrollTarget(index = targetIndex, scrollOffsetPx = intraOffsetPx)
}

/** Pins the thumb to the rail extremes when the list cannot scroll any further in that direction. */
internal fun resolveLazyListThumbFractionAtBoundaries(
    rawFraction: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): Float {
    val clamped = rawFraction.coerceIn(0f, 1f)
    return when {
        !canScrollForward -> 1f
        !canScrollBackward -> 0f
        else -> clamped
    }
}

@Composable
fun WithDraggableScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!enabled) {
        Box(modifier = Modifier.then(modifier), content = content)
        return
    }
    val thumbExtentPx = with(LocalDensity.current) { DraggableScrollbarThumbHeight.toPx() }
    val dragScope = rememberCoroutineScope()
    val canScroll by remember(state) {
        derivedStateOf { state.maxValue > 0 && state.viewportSize > 0 }
    }
    val thumbFraction by remember(state) {
        derivedStateOf {
            if (!canScroll) {
                0f
            } else {
                state.value.toFloat() / state.maxValue.toFloat()
            }
        }
    }
    val interaction =
        rememberDraggableScrollbarInteractionState(
            canScroll = canScroll,
            isScrollInProgress = state.isScrollInProgress,
        )

    Box(modifier = Modifier.then(modifier)) {
        content()
        DraggableScrollbarOverlay(
            visible = shouldShowDraggableScrollbar(canScroll = canScroll),
            isThumbDragged = interaction.isThumbDragged,
            isScrollInProgress = state.isScrollInProgress,
            recentlyScrolled = interaction.recentlyScrolled,
            thumbFraction = thumbFraction,
            thumbExtentPx = thumbExtentPx,
            onThumbFractionChanged = { fraction ->
                dragScope.launch {
                    state.scrollTo(
                        mapThumbFractionToScrollOffset(
                            thumbFraction = fraction,
                            maxScrollOffset = state.maxValue,
                        ),
                    )
                }
            },
            onDragStateChanged = { interaction.isThumbDragged = it },
        )
    }
}

@Composable
fun WithDraggableScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentGeneration: Any? = null,
    totalItemsCountOverride: Int? = null,
    scrollTargetItemsCountOverride: Int? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!enabled) {
        Box(modifier = Modifier.then(modifier), content = content)
        return
    }
    val thumbExtentPx = with(LocalDensity.current) { DraggableScrollbarThumbHeight.toPx() }
    val dragScope = rememberCoroutineScope()
    val lazyMetrics by produceState<LazyListScrollbarMetrics?>(
        initialValue = null,
        state,
        contentGeneration,
        totalItemsCountOverride,
        scrollTargetItemsCountOverride,
    ) {
        val estimator = LazyListScrollbarEstimator()
        snapshotFlow {
            state.layoutInfo.toLazyListScrollbarSnapshot(
                canScrollBackward = state.canScrollBackward,
                canScrollForward = state.canScrollForward,
            )
        }.collect { snapshot ->
            value =
                estimator.update(
                    snapshot = snapshot,
                    contentGeneration = contentGeneration,
                    totalItemsCountOverride = totalItemsCountOverride,
                    scrollTargetItemsCountOverride = scrollTargetItemsCountOverride,
                )
        }
    }
    val canScroll = lazyMetrics != null
    val thumbFraction = lazyMetrics?.scrollFraction ?: 0f
    val scrollRequestDispatcher =
        remember(state) {
            LazyListScrollRequestDispatcher { target ->
                CoroutineLazyListScrollRequest(
                    dragScope.launch {
                        state.scrollToItem(target.index, target.scrollOffsetPx)
                    },
                )
            }
        }
    DisposableEffect(scrollRequestDispatcher) {
        onDispose { scrollRequestDispatcher.cancelActiveRequest() }
    }
    val interaction =
        rememberDraggableScrollbarInteractionState(
            canScroll = canScroll,
            isScrollInProgress = state.isScrollInProgress,
        )

    Box(modifier = Modifier.then(modifier)) {
        content()
        DraggableScrollbarOverlay(
            visible = shouldShowDraggableScrollbar(canScroll = canScroll),
            isThumbDragged = interaction.isThumbDragged,
            isScrollInProgress = state.isScrollInProgress,
            recentlyScrolled = interaction.recentlyScrolled,
            thumbFraction = thumbFraction,
            thumbExtentPx = thumbExtentPx,
            onThumbFractionChanged = { fraction ->
                val metrics = lazyMetrics ?: return@DraggableScrollbarOverlay
                scrollRequestDispatcher.dispatch(metrics.targetForFraction(fraction))
            },
            onDragStateChanged = { interaction.isThumbDragged = it },
        )
    }
}

/**
 * Mutable scroll-fraction state for bridging Android View scroll positions
 * (e.g. EditText) to [WithDraggableScrollbar].
 */
@Stable
class ViewScrollState {
    var scrollFraction by mutableFloatStateOf(0f)
    var canScroll by mutableStateOf(false)
    var isScrollInProgress by mutableStateOf(false)
    var onScrollToFraction: ((Float) -> Unit)? = null
}

@Composable
fun WithDraggableScrollbar(
    state: ViewScrollState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!enabled) {
        Box(modifier = Modifier.then(modifier), content = content)
        return
    }
    val thumbExtentPx = with(LocalDensity.current) { DraggableScrollbarThumbHeight.toPx() }
    val interaction =
        rememberDraggableScrollbarInteractionState(
            canScroll = state.canScroll,
            isScrollInProgress = state.isScrollInProgress,
        )

    Box(modifier = Modifier.then(modifier)) {
        content()
        DraggableScrollbarOverlay(
            visible = shouldShowDraggableScrollbar(canScroll = state.canScroll),
            isThumbDragged = interaction.isThumbDragged,
            isScrollInProgress = state.isScrollInProgress,
            recentlyScrolled = interaction.recentlyScrolled,
            thumbFraction = state.scrollFraction,
            thumbExtentPx = thumbExtentPx,
            onThumbFractionChanged = { fraction ->
                state.onScrollToFraction?.invoke(fraction)
            },
            onDragStateChanged = { interaction.isThumbDragged = it },
        )
    }
}
