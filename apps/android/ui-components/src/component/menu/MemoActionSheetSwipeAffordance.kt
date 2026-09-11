package com.lomo.ui.component.menu

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

@Composable
internal fun rememberShowSwipeAffordanceIndicator(
    lazyRowState: LazyListState,
    useHorizontalScroll: Boolean,
    showSwipeAffordance: Boolean,
): State<Boolean> =
    remember(lazyRowState, useHorizontalScroll, showSwipeAffordance) {
        derivedStateOf {
            useHorizontalScroll &&
                showSwipeAffordance &&
                (lazyRowState.canScrollBackward || lazyRowState.canScrollForward)
        }
    }

@Composable
internal fun rememberSwipeAffordanceProgress(lazyRowState: LazyListState): State<Float> =
    remember(lazyRowState) {
        derivedStateOf { resolveLazyRowSwipeAffordanceProgress(lazyRowState) }
    }

internal fun resolveLazyRowSwipeAffordanceProgress(lazyRowState: LazyListState): Float {
    val layout = lazyRowState.layoutInfo
    val firstVisible = layout.visibleItemsInfo.firstOrNull()
    return resolveLazyRowSwipeAffordanceProgress(
        LazyRowSwipeAffordanceSnapshot(
            canScrollBackward = lazyRowState.canScrollBackward,
            canScrollForward = lazyRowState.canScrollForward,
            totalItemsCount = layout.totalItemsCount,
            firstVisibleItemIndex = firstVisible?.index,
            firstVisibleItemOffsetPx = firstVisible?.offset ?: 0,
            firstVisibleItemSizePx = firstVisible?.size ?: 0,
            viewportStartOffsetPx = layout.viewportStartOffset,
        ),
    )
}

internal data class LazyRowSwipeAffordanceSnapshot(
    val canScrollBackward: Boolean,
    val canScrollForward: Boolean,
    val totalItemsCount: Int,
    val firstVisibleItemIndex: Int?,
    val firstVisibleItemOffsetPx: Int = 0,
    val firstVisibleItemSizePx: Int = 0,
    val viewportStartOffsetPx: Int = 0,
)

internal fun resolveLazyRowSwipeAffordanceProgress(
    snapshot: LazyRowSwipeAffordanceSnapshot,
): Float {
    if (!snapshot.canScrollBackward && !snapshot.canScrollForward) {
        return 0f
    }
    if (!snapshot.canScrollForward) {
        return 1f
    }
    if (!snapshot.canScrollBackward) {
        return 0f
    }
    val totalItemsCount = snapshot.totalItemsCount
    if (totalItemsCount <= 1) {
        return 0f
    }
    val firstVisibleItemIndex = snapshot.firstVisibleItemIndex ?: return 0f
    val lastIndex = totalItemsCount - 1
    val safeIndex = firstVisibleItemIndex.coerceIn(0, lastIndex)
    val intraItemFraction =
        if (snapshot.firstVisibleItemSizePx <= 0) {
            0f
        } else {
            (snapshot.viewportStartOffsetPx - snapshot.firstVisibleItemOffsetPx)
                .coerceAtLeast(0)
                .toFloat() / snapshot.firstVisibleItemSizePx.toFloat()
        }.coerceIn(0f, 1f)
    return ((safeIndex.toFloat() + intraItemFraction) / lastIndex.toFloat()).coerceIn(0f, 1f)
}
