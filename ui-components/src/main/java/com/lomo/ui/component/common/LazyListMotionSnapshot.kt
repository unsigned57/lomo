package com.lomo.ui.component.common

import androidx.compose.foundation.lazy.LazyListLayoutInfo

data class LazyListMotionVisibleItem(
    val id: String,
    val index: Int,
    val offsetPx: Int,
    val sizePx: Int,
) {
    val bottomPx: Int = offsetPx + sizePx
}

data class LazyListMotionViewportSnapshot(
    val visibleItems: List<LazyListMotionVisibleItem>,
    val viewportStartPx: Int,
    val viewportEndPx: Int,
)

fun LazyListMotionViewportSnapshot.viewportVisibleIds(): Set<String> =
    visibleItems
        .asSequence()
        .filter { item -> item.bottomPx > viewportStartPx && item.offsetPx < viewportEndPx }
        .map(LazyListMotionVisibleItem::id)
        .toSet()

fun LazyListLayoutInfo.toLazyListMotionViewportSnapshot(): LazyListMotionViewportSnapshot =
    LazyListMotionViewportSnapshot(
        visibleItems =
            visibleItemsInfo.mapNotNull { item ->
                (item.key as? String)?.let { key ->
                    LazyListMotionVisibleItem(
                        id = key,
                        index = item.index,
                        offsetPx = item.offset,
                        sizePx = item.size,
                    )
                }
            },
        viewportStartPx = viewportStartOffset,
        viewportEndPx = viewportEndOffset,
    )
