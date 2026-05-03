package com.lomo.ui.component.common

import androidx.compose.foundation.lazy.LazyListLayoutInfo

internal data class LazyListScrollbarVisibleItem(
    val index: Int,
    val offset: Int,
    val size: Int,
)

internal data class LazyListScrollbarSnapshot(
    val totalItemsCount: Int,
    val viewportStartOffset: Int,
    val viewportEndOffset: Int,
    val canScrollBackward: Boolean,
    val canScrollForward: Boolean,
    val visibleItems: List<LazyListScrollbarVisibleItem>,
)

internal data class LazyListScrollbarMetrics(
    val totalItemsCount: Int,
    val viewportSizePx: Int,
    val avgItemSizePx: Float,
    val scrollFraction: Float,
    val measuredItemSizes: Map<Int, Int> = emptyMap(),
) {
    fun targetForFraction(fraction: Float): LazyListScrollTarget {
        if (avgItemSizePx <= 0f || totalItemsCount <= 0) {
            return LazyListScrollTarget(index = 0, scrollOffsetPx = 0)
        }
        val totalContentPx = estimatedTotalContentPx()
        val maxScrollPx =
            (totalContentPx - viewportSizePx.coerceAtLeast(0).toFloat()).coerceAtLeast(0f)
        val targetScrollPx = fraction.coerceIn(0f, 1f) * maxScrollPx
        return targetForScrollOffset(targetScrollPx)
    }

    private fun estimatedTotalContentPx(): Float {
        val knownEntries = measuredItemSizes.filterKeys { index -> index in 0 until totalItemsCount }
        val knownSizePx = knownEntries.values.sum().toFloat()
        val missingCount = (totalItemsCount - knownEntries.size).coerceAtLeast(0)
        return knownSizePx + missingCount * avgItemSizePx
    }

    private fun targetForScrollOffset(scrollOffsetPx: Float): LazyListScrollTarget {
        var remainingPx = scrollOffsetPx.coerceAtLeast(0f)
        repeat(totalItemsCount) { index ->
            val itemSizePx = measuredItemSizes[index]?.takeIf { it > 0 }?.toFloat() ?: avgItemSizePx
            if (remainingPx < itemSizePx || index == totalItemsCount - 1) {
                return LazyListScrollTarget(
                    index = index,
                    scrollOffsetPx = remainingPx.toInt().coerceAtLeast(0),
                )
            }
            remainingPx -= itemSizePx
        }
        return LazyListScrollTarget(index = 0, scrollOffsetPx = 0)
    }
}

internal class LazyListScrollbarEstimator {
    private val measuredItemSizes = mutableMapOf<Int, Int>()
    private var lastTotalItemsCount: Int = 0
    private var hasContentGeneration: Boolean = false
    private var lastContentGeneration: Any? = null
    private var lastPosition: LazyListEstimatedScrollPosition? = null
    private var lastFraction: Float? = null

    fun update(
        snapshot: LazyListScrollbarSnapshot,
        contentGeneration: Any? = null,
    ): LazyListScrollbarMetrics? {
        resetIfContentChanged(contentGeneration)
        if (snapshot.totalItemsCount < lastTotalItemsCount) {
            resetMeasurements()
        }
        lastTotalItemsCount = snapshot.totalItemsCount

        val viewportSize =
            (snapshot.viewportEndOffset - snapshot.viewportStartOffset).coerceAtLeast(0)
        if (snapshot.visibleItems.isEmpty() || viewportSize <= 0 || snapshot.totalItemsCount <= 0) {
            clearScrollAnchor()
            return null
        }
        recordVisibleItemSizes(snapshot.visibleItems)
        if (!snapshot.canScrollBackward && !snapshot.canScrollForward) {
            clearScrollAnchor()
            return null
        }

        val avgItemSizePx = averageMeasuredItemSizePx() ?: return null
        val firstVisible = snapshot.visibleItems.first()
        val firstVisibleItemScrollOffsetPx =
            (snapshot.viewportStartOffset - firstVisible.offset).coerceAtLeast(0)
        val currentPosition =
            LazyListEstimatedScrollPosition(
                firstVisibleItemIndex = firstVisible.index,
                firstVisibleItemScrollOffsetPx = firstVisibleItemScrollOffsetPx,
            )
        val scrollOffsetPx =
            estimateScrollOffsetPx(
                firstVisibleItemIndex = firstVisible.index,
                firstVisibleItemScrollOffsetPx = firstVisibleItemScrollOffsetPx,
                avgItemSizePx = avgItemSizePx,
            )
        val totalContentPx =
            estimateTotalContentPx(
                totalItemsCount = snapshot.totalItemsCount,
                avgItemSizePx = avgItemSizePx,
            )
        val maxScrollPx = (totalContentPx - viewportSize.toFloat()).coerceAtLeast(0f)
        val rawFraction =
            if (maxScrollPx <= 0f) {
                0f
            } else {
                (scrollOffsetPx / maxScrollPx).coerceIn(0f, 1f)
            }
        val boundaryFraction =
            resolveLazyListThumbFractionAtBoundaries(
                rawFraction = rawFraction,
                canScrollBackward = snapshot.canScrollBackward,
                canScrollForward = snapshot.canScrollForward,
            )
        val fraction =
            resolveMonotonicFraction(
                rawFraction = boundaryFraction,
                direction =
                    resolveScrollDirection(
                        previous = lastPosition,
                        current = currentPosition,
                    ),
            )
        lastPosition = currentPosition
        lastFraction = fraction

        return LazyListScrollbarMetrics(
            totalItemsCount = snapshot.totalItemsCount,
            viewportSizePx = viewportSize,
            avgItemSizePx = avgItemSizePx,
            scrollFraction = fraction,
            measuredItemSizes = measuredItemSizes.toMap(),
        )
    }

    private fun resetIfContentChanged(contentGeneration: Any?) {
        if (!hasContentGeneration || lastContentGeneration == contentGeneration) {
            hasContentGeneration = true
            lastContentGeneration = contentGeneration
            return
        }
        resetMeasurements()
        lastContentGeneration = contentGeneration
    }

    private fun resetMeasurements() {
        measuredItemSizes.clear()
        clearScrollAnchor()
    }

    private fun clearScrollAnchor() {
        lastPosition = null
        lastFraction = null
    }

    private fun recordVisibleItemSizes(visibleItems: List<LazyListScrollbarVisibleItem>) {
        visibleItems.forEach { item ->
            if (item.index >= 0 && item.size > 0) {
                measuredItemSizes[item.index] = item.size
            }
        }
    }

    private fun averageMeasuredItemSizePx(): Float? {
        if (measuredItemSizes.isEmpty()) return null
        return measuredItemSizes.values.sum().toFloat() / measuredItemSizes.size
    }

    private fun estimateScrollOffsetPx(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffsetPx: Int,
        avgItemSizePx: Float,
    ): Float =
        estimateItemStartPx(index = firstVisibleItemIndex, avgItemSizePx = avgItemSizePx) +
            firstVisibleItemScrollOffsetPx.coerceAtLeast(0).toFloat()

    private fun estimateItemStartPx(index: Int, avgItemSizePx: Float): Float {
        val safeIndex = index.coerceAtLeast(0)
        var knownSizePx = 0f
        var knownCount = 0
        measuredItemSizes.forEach { (measuredIndex, measuredSize) ->
            if (measuredIndex in 0 until safeIndex) {
                knownSizePx += measuredSize
                knownCount += 1
            }
        }
        val missingCount = (safeIndex - knownCount).coerceAtLeast(0)
        return knownSizePx + missingCount * avgItemSizePx
    }

    private fun estimateTotalContentPx(totalItemsCount: Int, avgItemSizePx: Float): Float {
        val knownEntries =
            measuredItemSizes.filterKeys { index -> index in 0 until totalItemsCount }
        val knownSizePx = knownEntries.values.sum().toFloat()
        val missingCount = (totalItemsCount - knownEntries.size).coerceAtLeast(0)
        return knownSizePx + missingCount * avgItemSizePx
    }

    private fun resolveMonotonicFraction(
        rawFraction: Float,
        direction: LazyListEstimatedScrollDirection,
    ): Float {
        val previousFraction = lastFraction ?: return rawFraction
        return when (direction) {
            LazyListEstimatedScrollDirection.Forward -> rawFraction.coerceAtLeast(previousFraction)
            LazyListEstimatedScrollDirection.Backward -> rawFraction.coerceAtMost(previousFraction)
            LazyListEstimatedScrollDirection.None -> previousFraction
        }
    }
}

internal fun LazyListLayoutInfo.toLazyListScrollbarSnapshot(
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): LazyListScrollbarSnapshot =
    LazyListScrollbarSnapshot(
        totalItemsCount = totalItemsCount,
        viewportStartOffset = viewportStartOffset,
        viewportEndOffset = viewportEndOffset,
        canScrollBackward = canScrollBackward,
        canScrollForward = canScrollForward,
        visibleItems =
            visibleItemsInfo.map { item ->
                LazyListScrollbarVisibleItem(
                    index = item.index,
                    offset = item.offset,
                    size = item.size,
                )
            },
    )

private data class LazyListEstimatedScrollPosition(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffsetPx: Int,
)

private enum class LazyListEstimatedScrollDirection {
    Forward,
    Backward,
    None,
}

private fun resolveScrollDirection(
    previous: LazyListEstimatedScrollPosition?,
    current: LazyListEstimatedScrollPosition,
): LazyListEstimatedScrollDirection {
    if (previous == null) return LazyListEstimatedScrollDirection.None
    return when {
        current.firstVisibleItemIndex > previous.firstVisibleItemIndex ->
            LazyListEstimatedScrollDirection.Forward
        current.firstVisibleItemIndex < previous.firstVisibleItemIndex ->
            LazyListEstimatedScrollDirection.Backward
        current.firstVisibleItemScrollOffsetPx > previous.firstVisibleItemScrollOffsetPx ->
            LazyListEstimatedScrollDirection.Forward
        current.firstVisibleItemScrollOffsetPx < previous.firstVisibleItemScrollOffsetPx ->
            LazyListEstimatedScrollDirection.Backward
        else -> LazyListEstimatedScrollDirection.None
    }
}
