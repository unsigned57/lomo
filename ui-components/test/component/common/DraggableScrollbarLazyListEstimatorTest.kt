package com.lomo.ui.component.common

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Test Contract:
 * - Unit under test: LazyListScrollbarEstimator and known-size scroll target mapping.
 * - Behavior focus: main memo lists contain highly variable card heights, so scrollbar progress
 *   must stay stable while new measurements enter the cache, pin to real list boundaries, and
 *   reset stale size observations when the content generation changes. Paged lists must also be
 *   able to use a repository-backed total count instead of the currently materialized page count.
 * - Observable outcomes: resolved thumb fraction, measured average after a reset, and drag target
 *   index/offset produced from known item sizes.
 * - Red phase: Fails before the fix because LazyList scrollbar progress is computed from a
 *   simple seen-item average with no stateful monotonic guard, no content-generation reset, and
 *   no known-size prefix mapping for drag targets. It also fails before the paging-total fix
 *   because the estimator cannot override LazyColumn's loaded item count with the repository count.
 * - Excludes: Compose LazyColumn rendering, pointer input dispatch, and scrollbar canvas pixels.
 */
class DraggableScrollbarLazyListEstimatorTest : UiComponentsFunSpec() {
    init {
        test("forward scroll keeps thumb from moving backward when a tall memo enters the cache") {
        val estimator = LazyListScrollbarEstimator()
        val beforeTallMemo =
            estimator.update(
                snapshot(
                    firstIndex = 20,
                    itemSizes = listOf(100, 100, 100, 100, 100),
                ),
                contentGeneration = "memos",
        )
        (beforeTallMemo) shouldNotBe null
        val before = checkNotNull(beforeTallMemo)

        val afterTallMemo =
            estimator.update(
                snapshot(
                    firstIndex = 21,
                    itemSizes = listOf(600, 100, 100, 100, 100),
                ),
                contentGeneration = "memos",
        )
        (afterTallMemo) shouldNotBe null
        val after = checkNotNull(afterTallMemo)

        withClue("Expected forward scrolling to keep the scrollbar fraction monotonic, " +
                "but before=${before.scrollFraction} and after=${after.scrollFraction}.") { (after.scrollFraction >= before.scrollFraction) shouldBe true }
        }
    }

    init {
        test("lazy list estimator pins thumb to bottom when forward scrolling is blocked") {
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

        (metrics) shouldNotBe null
        (metrics!!.scrollFraction) shouldBe ((1f) plusOrMinus (0.001f))
        }
    }

    init {
        test("content generation change clears stale measured sizes") {
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

        (resetMetrics) shouldNotBe null
        (resetMetrics!!.avgItemSizePx) shouldBe ((100f) plusOrMinus (0.001f))
        }
    }

    init {
        test("external total keeps paging append from moving thumb upward") {
        val estimator = LazyListScrollbarEstimator()
        val beforeAppend =
            estimator.update(
                snapshot(
                    firstIndex = 10,
                    totalItemsCount = 20,
                    itemSizes = listOf(100, 100, 100, 100),
                ),
                contentGeneration = "main-feed",
                totalItemsCountOverride = 100,
            )
        (beforeAppend) shouldNotBe null
        val before = checkNotNull(beforeAppend)

        val afterAppend =
            estimator.update(
                snapshot(
                    firstIndex = 10,
                    totalItemsCount = 40,
                    itemSizes = listOf(100, 100, 100, 100),
                ),
                contentGeneration = "main-feed",
                totalItemsCountOverride = 100,
            )
        (afterAppend) shouldNotBe null
        val after = checkNotNull(afterAppend)

        (before.totalItemsCount) shouldBe (100)
        (after.totalItemsCount) shouldBe (100)
        (after.scrollFraction) shouldBe ((before.scrollFraction) plusOrMinus (0.001f))
        }
    }

    init {
        test("drag target clamps to materialized rows when repository total is larger than loaded page") {
        val metrics =
            LazyListScrollbarMetrics(
                totalItemsCount = 100,
                scrollTargetItemsCount = 20,
                viewportSizePx = 1_000,
                avgItemSizePx = 100f,
                scrollFraction = 0f,
            )

        val target = metrics.targetForFraction(1f)

        (target.index) shouldBe (19)
        (target.scrollOffsetPx) shouldBe (0)
        }
    }

    init {
        test("known-size target mapping lands in the correct heterogeneous memo range") {
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

        (target.index) shouldBe (65)
        (target.scrollOffsetPx) shouldBe (0)
        }
    }

    private fun snapshot(
        firstIndex: Int,
        totalItemsCount: Int = 100,
        itemSizes: List<Int>,
        canScrollBackward: Boolean = true,
        canScrollForward: Boolean = true,
    ): LazyListScrollbarSnapshot =
        LazyListScrollbarSnapshot(
            totalItemsCount = totalItemsCount,
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
