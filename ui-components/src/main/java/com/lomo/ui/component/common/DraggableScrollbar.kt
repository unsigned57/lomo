package com.lomo.ui.component.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

internal const val DRAGGABLE_SCROLLBAR_FADE_OUT_DELAY_MS = 1200L
internal val DraggableScrollbarTrackPadding = 2.dp
internal val DraggableScrollbarIdleWidth = 4.dp
internal val DraggableScrollbarDragWidth = 6.dp
internal val DraggableScrollbarTouchTargetWidth = 24.dp
internal val DraggableScrollbarThumbHeight = 36.dp
internal const val DRAGGABLE_SCROLLBAR_IDLE_ALPHA = 0.25f
internal const val DRAGGABLE_SCROLLBAR_DRAG_ALPHA = 0.45f

internal fun shouldShowDraggableScrollbar(
    canScroll: Boolean,
    isScrollInProgress: Boolean,
    isThumbDragged: Boolean,
    recentlyScrolled: Boolean,
): Boolean = canScroll && (isScrollInProgress || isThumbDragged || recentlyScrolled)

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

internal fun mapThumbFractionToLazyListPosition(
    thumbFraction: Float,
    totalItemsCount: Int,
    targetItemSizePx: Int?,
): LazyListScrollTarget {
    val safeTotalItemsCount = totalItemsCount.coerceAtLeast(0)
    if (safeTotalItemsCount <= 1) {
        return LazyListScrollTarget(index = 0, scrollOffsetPx = 0)
    }
    val lastIndex = safeTotalItemsCount - 1
    val scaledIndex = thumbFraction.coerceIn(0f, 1f) * lastIndex.toFloat()
    val index = floor(scaledIndex).toInt().coerceIn(0, lastIndex)
    val intraItemFraction = (scaledIndex - index.toFloat()).coerceIn(0f, 1f)
    val intraItemOffset =
        targetItemSizePx
            ?.coerceAtLeast(0)
            ?.let { itemSize ->
                (itemSize * intraItemFraction)
                    .roundToInt()
                    .coerceIn(0, itemSize.coerceAtLeast(1) - 1)
            } ?: 0
    return LazyListScrollTarget(
        index = index,
        scrollOffsetPx = intraItemOffset,
    )
}

internal fun resolveLazyListThumbFraction(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    firstVisibleItemSizePx: Int,
    totalItemsCount: Int,
): Float {
    val safeTotalItemsCount = totalItemsCount.coerceAtLeast(0)
    if (safeTotalItemsCount <= 1) {
        return 0f
    }
    val lastIndex = safeTotalItemsCount - 1
    val safeIndex = firstVisibleItemIndex.coerceIn(0, lastIndex)
    val intraItemFraction =
        if (firstVisibleItemSizePx <= 0) {
            0f
        } else {
            firstVisibleItemScrollOffsetPx
                .coerceAtLeast(0)
                .toFloat() / firstVisibleItemSizePx.toFloat()
        }.coerceIn(0f, 1f)
    return ((safeIndex.toFloat() + intraItemFraction) / lastIndex.toFloat()).coerceIn(0f, 1f)
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
            visible =
                shouldShowDraggableScrollbar(
                    canScroll = canScroll,
                    isScrollInProgress = state.isScrollInProgress,
                    isThumbDragged = interaction.isThumbDragged,
                    recentlyScrolled = interaction.recentlyScrolled,
                ),
            isThumbDragged = interaction.isThumbDragged,
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
    content: @Composable BoxScope.() -> Unit,
) {
    if (!enabled) {
        Box(modifier = Modifier.then(modifier), content = content)
        return
    }
    val thumbExtentPx = with(LocalDensity.current) { DraggableScrollbarThumbHeight.toPx() }
    val dragScope = rememberCoroutineScope()
    val lazyMetrics by remember(state) {
        derivedStateOf { state.layoutInfo.toLazyListScrollbarMetrics() }
    }
    val canScroll = lazyMetrics != null
    val thumbFraction = lazyMetrics?.scrollFraction ?: 0f
    val interaction =
        rememberDraggableScrollbarInteractionState(
            canScroll = canScroll,
            isScrollInProgress = state.isScrollInProgress,
        )

    Box(modifier = Modifier.then(modifier)) {
        content()
        DraggableScrollbarOverlay(
            visible =
                shouldShowDraggableScrollbar(
                    canScroll = canScroll,
                    isScrollInProgress = state.isScrollInProgress,
                    isThumbDragged = interaction.isThumbDragged,
                    recentlyScrolled = interaction.recentlyScrolled,
                ),
            isThumbDragged = interaction.isThumbDragged,
            thumbFraction = thumbFraction,
            thumbExtentPx = thumbExtentPx,
            onThumbFractionChanged = { fraction ->
                val metrics = lazyMetrics ?: return@DraggableScrollbarOverlay
                val targetIndex =
                    floor(
                        fraction.coerceIn(0f, 1f) *
                            (metrics.totalItemsCount - 1).coerceAtLeast(0).toFloat(),
                    ).toInt()
                val target =
                    mapThumbFractionToLazyListPosition(
                        thumbFraction = fraction,
                        totalItemsCount = metrics.totalItemsCount,
                        targetItemSizePx = metrics.visibleItemSizePxByIndex[targetIndex],
                    )
                dragScope.launch {
                    state.scrollToItem(target.index, target.scrollOffsetPx)
                }
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
            visible =
                shouldShowDraggableScrollbar(
                    canScroll = state.canScroll,
                    isScrollInProgress = state.isScrollInProgress,
                    isThumbDragged = interaction.isThumbDragged,
                    recentlyScrolled = interaction.recentlyScrolled,
                ),
            isThumbDragged = interaction.isThumbDragged,
            thumbFraction = state.scrollFraction,
            thumbExtentPx = thumbExtentPx,
            onThumbFractionChanged = { fraction ->
                state.onScrollToFraction?.invoke(fraction)
            },
            onDragStateChanged = { interaction.isThumbDragged = it },
        )
    }
}

private data class LazyListScrollbarMetrics(
    val totalItemsCount: Int,
    val scrollFraction: Float,
    val visibleItemSizePxByIndex: Map<Int, Int>,
)

private fun androidx.compose.foundation.lazy.LazyListLayoutInfo
    .toLazyListScrollbarMetrics(): LazyListScrollbarMetrics? {
    val visibleItems = visibleItemsInfo
    if (visibleItems.isEmpty()) {
        return null
    }
    val viewportSize = (viewportEndOffset - viewportStartOffset).coerceAtLeast(0)
    if (viewportSize <= 0 || totalItemsCount <= 0) {
        return null
    }
    val firstVisible = visibleItems.first()
    val lastVisible = visibleItems.last()
    val canScroll =
        firstVisible.index > 0 ||
            firstVisible.offset < viewportStartOffset ||
            lastVisible.index < totalItemsCount - 1 ||
            (lastVisible.offset + lastVisible.size) > viewportEndOffset
    if (!canScroll) {
        return null
    }
    val firstVisibleItemScrollOffsetPx = (viewportStartOffset - firstVisible.offset).coerceAtLeast(0)
    return LazyListScrollbarMetrics(
        totalItemsCount = totalItemsCount,
        scrollFraction =
            resolveLazyListThumbFraction(
                firstVisibleItemIndex = firstVisible.index,
                firstVisibleItemScrollOffsetPx = firstVisibleItemScrollOffsetPx,
                firstVisibleItemSizePx = firstVisible.size,
                totalItemsCount = totalItemsCount,
            ),
        visibleItemSizePxByIndex = visibleItems.associate { it.index to it.size },
    )
}
