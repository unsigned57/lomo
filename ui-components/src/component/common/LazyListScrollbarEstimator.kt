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
    val scrollTargetItemsCount: Int = totalItemsCount,
    val viewportSizePx: Int,
    val effectiveVisibleSpan: Float,
    val scrollFraction: Float,
    val firstVisibleItemSizePx: Int = 0,
) {
    fun targetForFraction(fraction: Float): LazyListScrollTarget {
        if (totalItemsCount <= 0) {
            return LazyListScrollTarget(index = 0, scrollOffsetPx = 0)
        }
        val safeFraction = fraction.coerceIn(0f, 1f)
        val maxScrollableIndex =
            (totalItemsCount.toFloat() - effectiveVisibleSpan.coerceAtLeast(1f)).coerceAtLeast(1f)
        val targetContinuousIndex = safeFraction * maxScrollableIndex
        val targetIndex = targetContinuousIndex.toInt().coerceIn(0, totalItemsCount - 1)
        val intraFraction = (targetContinuousIndex - targetIndex).coerceIn(0f, 1f)
        val intraOffsetPx =
            if (firstVisibleItemSizePx > 0) {
                (intraFraction * firstVisibleItemSizePx).toInt().coerceAtLeast(0)
            } else {
                0
            }
        return clampTargetToMaterializedItems(
            LazyListScrollTarget(
                index = targetIndex,
                scrollOffsetPx = intraOffsetPx,
            ),
        )
    }

    private fun clampTargetToMaterializedItems(target: LazyListScrollTarget): LazyListScrollTarget {
        if (scrollTargetItemsCount <= 0) {
            return LazyListScrollTarget(index = 0, scrollOffsetPx = 0)
        }
        val maxTargetIndex =
            (scrollTargetItemsCount - 1)
                .coerceAtMost((totalItemsCount - 1).coerceAtLeast(0))
                .coerceAtLeast(0)
        return if (target.index <= maxTargetIndex) {
            target
        } else {
            LazyListScrollTarget(index = maxTargetIndex, scrollOffsetPx = 0)
        }
    }
}

internal class LazyListScrollbarEstimator {
    fun update(
        snapshot: LazyListScrollbarSnapshot,
        totalItemsCountOverride: Int? = null,
        scrollTargetItemsCountOverride: Int? = null,
    ): LazyListScrollbarMetrics? {
        val totalItemsCount =
            resolveEffectiveTotalItemsCount(
                snapshotTotalItemsCount = snapshot.totalItemsCount,
                totalItemsCountOverride = totalItemsCountOverride,
            )
        val viewportSize =
            (snapshot.viewportEndOffset - snapshot.viewportStartOffset).coerceAtLeast(0)
        if (snapshot.visibleItems.isEmpty() || viewportSize <= 0 || totalItemsCount <= 0) {
            return null
        }
        val canScrollForward = snapshot.canScrollForward || totalItemsCount > snapshot.totalItemsCount
        if (!snapshot.canScrollBackward && !canScrollForward) {
            return null
        }

        val firstVisible = snapshot.visibleItems.first()
        val firstVisibleItemScrollOffsetPx =
            (snapshot.viewportStartOffset - firstVisible.offset).coerceAtLeast(0)
        val firstVisibleItemSizePx = firstVisible.size.coerceAtLeast(1)
        val intraFirst =
            (firstVisibleItemScrollOffsetPx.toFloat() / firstVisibleItemSizePx.toFloat()).coerceIn(0f, 0.999f)
        val firstProgress = firstVisible.index.coerceAtLeast(0).toFloat() + intraFirst

        val lastVisible = snapshot.visibleItems.last()
        val lastVisibleItemVisiblePx =
            (snapshot.viewportEndOffset - lastVisible.offset).coerceIn(0, lastVisible.size)
        val lastVisibleItemSizePx = lastVisible.size.coerceAtLeast(1)
        val intraLast =
            (lastVisibleItemVisiblePx.toFloat() / lastVisibleItemSizePx.toFloat()).coerceIn(0f, 1f)
        val lastProgress = lastVisible.index.coerceAtLeast(0).toFloat() + intraLast

        val effectiveSpan = (lastProgress - firstProgress).coerceAtLeast(1f)
        val maxScrollableIndex =
            (totalItemsCount.toFloat() - effectiveSpan).coerceAtLeast(1f)

        val rawFraction = (firstProgress / maxScrollableIndex).coerceIn(0f, 1f)
        val boundaryFraction =
            resolveLazyListThumbFractionAtBoundaries(
                rawFraction = rawFraction,
                canScrollBackward = snapshot.canScrollBackward,
                canScrollForward = canScrollForward,
            )

        return LazyListScrollbarMetrics(
            totalItemsCount = totalItemsCount,
            scrollTargetItemsCount =
                resolveScrollTargetItemsCount(
                    totalItemsCount = totalItemsCount,
                    scrollTargetItemsCountOverride = scrollTargetItemsCountOverride,
                ),
            viewportSizePx = viewportSize,
            effectiveVisibleSpan = effectiveSpan,
            scrollFraction = boundaryFraction,
            firstVisibleItemSizePx = firstVisibleItemSizePx,
        )
    }
}

private fun resolveEffectiveTotalItemsCount(
    snapshotTotalItemsCount: Int,
    totalItemsCountOverride: Int?,
): Int =
    maxOf(
        snapshotTotalItemsCount.coerceAtLeast(0),
        totalItemsCountOverride?.coerceAtLeast(0) ?: 0,
    )

private fun resolveScrollTargetItemsCount(
    totalItemsCount: Int,
    scrollTargetItemsCountOverride: Int?,
): Int =
    (scrollTargetItemsCountOverride ?: totalItemsCount)
        .coerceAtLeast(0)
        .coerceAtMost(totalItemsCount)

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
