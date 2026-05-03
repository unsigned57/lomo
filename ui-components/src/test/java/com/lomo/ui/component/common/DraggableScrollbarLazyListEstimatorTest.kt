package com.lomo.ui.component.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: LazyListScrollbarEstimator and known-size scroll target mapping.
 * - Behavior focus: main memo lists contain highly variable card heights, so scrollbar progress
 *   must stay stable while new measurements enter the cache, pin to real list boundaries, and
 *   reset stale size observations when the content generation changes.
 * - Observable outcomes: resolved thumb fraction, measured average after a reset, and drag target
 *   index/offset produced from known item sizes.
 * - Red phase: Fails before the fix because LazyList scrollbar progress is computed from a
 *   simple seen-item average with no stateful monotonic guard, no content-generation reset, and
 *   no known-size prefix mapping for drag targets.
 * - Excludes: Compose LazyColumn rendering, pointer input dispatch, and scrollbar canvas pixels.
 */
class DraggableScrollbarLazyListEstimatorTest {
    @Test
    fun `forward scroll keeps thumb from moving backward when a tall memo enters the cache`() {
        val estimator = LazyListScrollbarEstimator()
        val beforeTallMemo =
            estimator.update(
                snapshot(
                    firstIndex = 20,
                    itemSizes = listOf(100, 100, 100, 100, 100),
                ),
                contentGeneration = "memos",
        )
        assertNotNull(beforeTallMemo)
        val before = checkNotNull(beforeTallMemo)

        val afterTallMemo =
            estimator.update(
                snapshot(
                    firstIndex = 21,
                    itemSizes = listOf(600, 100, 100, 100, 100),
                ),
                contentGeneration = "memos",
        )
        assertNotNull(afterTallMemo)
        val after = checkNotNull(afterTallMemo)

        assertTrue(
            "Expected forward scrolling to keep the scrollbar fraction monotonic, " +
                "but before=${before.scrollFraction} and after=${after.scrollFraction}.",
            after.scrollFraction >= before.scrollFraction,
        )
    }

    @Test
    fun `lazy list estimator pins thumb to bottom when forward scrolling is blocked`() {
        val estimator = LazyListScrollbarEstimator()

        val metrics =
            estimator.update(
                snapshot(
                    firstIndex = 64,
                    itemSizes = listOf(120, 120, 120, 120),
                    canScrollForward = false,
                ),
                contentGeneration = "memos",
            )

        assertNotNull(metrics)
        assertEquals(1f, metrics!!.scrollFraction, 0.001f)
    }

    @Test
    fun `content generation change clears stale measured sizes`() {
        val estimator = LazyListScrollbarEstimator()
        estimator.update(
            snapshot(
                firstIndex = 0,
                itemSizes = listOf(1_000, 1_000),
            ),
            contentGeneration = "before-filter",
        )

        val resetMetrics =
            estimator.update(
                snapshot(
                    firstIndex = 0,
                    itemSizes = listOf(100, 100, 100),
                ),
                contentGeneration = "after-filter",
            )

        assertNotNull(resetMetrics)
        assertEquals(100f, resetMetrics!!.avgItemSizePx, 0.001f)
    }

    @Test
    fun `known-size target mapping lands in the correct heterogeneous memo range`() {
        val knownSizes =
            buildMap {
                repeat(50) { index -> put(index, 100) }
                repeat(50) { offset -> put(50 + offset, 300) }
            }
        val metrics =
            LazyListScrollbarMetrics(
                totalItemsCount = 100,
                viewportSizePx = 1_000,
                avgItemSizePx = 200f,
                scrollFraction = 0f,
                measuredItemSizes = knownSizes,
            )

        val target = metrics.targetForFraction(0.5f)

        assertEquals(65, target.index)
        assertEquals(0, target.scrollOffsetPx)
    }

    private fun snapshot(
        firstIndex: Int,
        itemSizes: List<Int>,
        canScrollBackward: Boolean = true,
        canScrollForward: Boolean = true,
    ): LazyListScrollbarSnapshot =
        LazyListScrollbarSnapshot(
            totalItemsCount = 100,
            viewportStartOffset = 0,
            viewportEndOffset = 1_000,
            canScrollBackward = canScrollBackward,
            canScrollForward = canScrollForward,
            visibleItems =
                itemSizes.mapIndexed { offset, size ->
                    LazyListScrollbarVisibleItem(
                        index = firstIndex + offset,
                        offset = itemSizes.take(offset).sum(),
                        size = size,
                    )
                },
        )
}
