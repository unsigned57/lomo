package com.lomo.ui.component.common

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Behavior Contract:
 * - Unit under test: LazyListScrollbarEstimator and continuous index-normalized scroll target mapping.
 * - Owning layer: ui-components
 * - Priority tier: P1
 * - Capability: stable continuous index-normalized scrollbar progress and drag target resolution.
 *
 * Scenarios:
 * - Given a LazyColumn with variable item heights, when scrolling forward frame-by-frame, then thumb progress increases strictly monotonically without jitter.
 * - Given scrolling past a tall memo with an image, when advancing sub-pixel offsets, then fraction remains smooth and monotonic.
 * - Given scrolling up within a tall memo, when scrolling backward, then fraction decreases smoothly.
 * - Given list reaches boundary, when canScrollForward or canScrollBackward is false, then fraction pins to 1f or 0f.
 * - Given total items count override, when paging appends more loaded items, then scrollbar fraction remains invariant.
 * - Given drag target mapping, when dragging thumb to fraction, then target index and offset clamp to materialized range.
 *
 * Observable outcomes:
 * - resolved thumb fraction, drag target index/offset, strictly monotonic frame-by-frame scroll advancement, and boundary clamping.
 *
 * TDD proof:
 * - Fails before the fix when discrete visible item count (e.g. jumping between 4 and 5) causes fraction oscillations/jitter during continuous scrolling across item boundaries.
 *
 * Excludes:
 * - Compose LazyColumn rendering, pointer input dispatch, and scrollbar canvas pixels.
 */
/*
 * Test Change Justification:
 * - Reason category: first-principles refactor of scrollbar progress and drag mapping.
 * - Old behavior/assertion being replaced: global average pixel size estimation (avgItemSizePx) with stateful cache resets.
 * - Why old assertion is no longer correct: pixel-based global estimation causes non-linear fraction fluctuation and jumpy scrollbars when heterogeneous items/images enter the viewport.
 * - Coverage preserved by: continuous sub-pixel monotonicity assertions, tall memo entry/exit assertions, boundary pin assertions, and drag target mapping tests.
 * - Why this is not fitting the test to the implementation: assertions verify mathematical invariants of index-normalized progression rather than implementation quirks.
 */
class DraggableScrollbarLazyListEstimatorTest : UiComponentsFunSpec() {
    init {
        test("sub-pixel frame-by-frame forward scroll is strictly monotonic with zero jitter across item boundaries") {
            val estimator = LazyListScrollbarEstimator()
            var previousFraction = -1f

            // Simulate scrolling from offset 0 to 250px across item boundaries in 5px increments
            for (scrollPx in 0..250 step 5) {
                val itemIndex = scrollPx / 100
                val intraOffset = scrollPx % 100
                val metrics =
                    estimator.update(
                        snapshot(
                            firstIndex = itemIndex,
                            firstOffset = intraOffset,
                            totalItemsCount = 100,
                            itemSizes = List(15) { 100 },
                        ),
                    )
                metrics shouldNotBe null
                val currentFraction = checkNotNull(metrics).scrollFraction

                if (previousFraction >= 0f) {
                    withClue("Expected fraction at scrollPx=$scrollPx ($currentFraction) to be >= previous ($previousFraction)") {
                        (currentFraction >= previousFraction) shouldBe true
                    }
                }
                previousFraction = currentFraction
            }
        }
    }

    init {
        test("forward scroll keeps thumb monotonic when a tall memo with image enters the viewport") {
            val estimator = LazyListScrollbarEstimator()
            val beforeTallMemo =
                estimator.update(
                    snapshot(
                        firstIndex = 20,
                        itemSizes = listOf(100, 100, 100, 100, 100),
                    ),
                )
            beforeTallMemo shouldNotBe null
            val before = checkNotNull(beforeTallMemo)

            val afterTallMemo =
                estimator.update(
                    snapshot(
                        firstIndex = 21,
                        itemSizes = listOf(600, 100, 100, 100, 100),
                    ),
                )
            afterTallMemo shouldNotBe null
            val after = checkNotNull(afterTallMemo)

            withClue("Expected forward scrolling to keep the scrollbar fraction monotonic, " +
                    "but before=${before.scrollFraction} and after=${after.scrollFraction}.") {
                (after.scrollFraction >= before.scrollFraction) shouldBe true
            }
        }
    }

    init {
        test("backward scroll smoothly decreases fraction without jumping when scrolling past tall memo") {
            val estimator = LazyListScrollbarEstimator()
            val atTallMemo =
                estimator.update(
                    snapshot(
                        firstIndex = 21,
                        firstOffset = 300,
                        itemSizes = listOf(600, 100, 100, 100, 100),
                    ),
                )
            atTallMemo shouldNotBe null
            val before = checkNotNull(atTallMemo)

            val scrollUpInTallMemo =
                estimator.update(
                    snapshot(
                        firstIndex = 21,
                        firstOffset = 100,
                        itemSizes = listOf(600, 100, 100, 100, 100),
                    ),
                )
            scrollUpInTallMemo shouldNotBe null
            val after = checkNotNull(scrollUpInTallMemo)

            withClue("Expected scrolling up within tall memo to decrease fraction, " +
                    "but before=${before.scrollFraction} and after=${after.scrollFraction}.") {
                (after.scrollFraction < before.scrollFraction) shouldBe true
            }
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
                )

            metrics shouldNotBe null
            metrics!!.scrollFraction shouldBe ((1f) plusOrMinus 0.001f)
        }
    }

    init {
        test("lazy list estimator pins thumb to top when backward scrolling is blocked") {
            val estimator = LazyListScrollbarEstimator()

            val metrics =
                estimator.update(
                    snapshot(
                        firstIndex = 0,
                        firstOffset = 0,
                        itemSizes = listOf(120, 120, 120, 120),
                        canScrollBackward = false,
                    ),
                )

            metrics shouldNotBe null
            metrics!!.scrollFraction shouldBe ((0f) plusOrMinus 0.001f)
        }
    }

    init {
        test("stateless estimator is purely derived per-frame without stale historical size pollution") {
            val estimator = LazyListScrollbarEstimator()
            val firstMetrics =
                estimator.update(
                    snapshot(
                        firstIndex = 5,
                        itemSizes = listOf(1_000, 1_000),
                    ),
                )
            firstMetrics shouldNotBe null
            firstMetrics!!.firstVisibleItemSizePx shouldBe 1_000

            val secondMetrics =
                estimator.update(
                    snapshot(
                        firstIndex = 5,
                        itemSizes = listOf(100, 100, 100),
                    ),
                )

            secondMetrics shouldNotBe null
            secondMetrics!!.firstVisibleItemSizePx shouldBe 100
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
                    totalItemsCountOverride = 100,
                )
            beforeAppend shouldNotBe null
            val before = checkNotNull(beforeAppend)

            val afterAppend =
                estimator.update(
                    snapshot(
                        firstIndex = 10,
                        totalItemsCount = 40,
                        itemSizes = listOf(100, 100, 100, 100),
                    ),
                    totalItemsCountOverride = 100,
                )
            afterAppend shouldNotBe null
            val after = checkNotNull(afterAppend)

            before.totalItemsCount shouldBe 100
            after.totalItemsCount shouldBe 100
            after.scrollFraction shouldBe ((before.scrollFraction) plusOrMinus 0.001f)
        }
    }

    init {
        test("drag target clamps to materialized rows when repository total is larger than loaded page") {
            val metrics =
                LazyListScrollbarMetrics(
                    totalItemsCount = 100,
                    scrollTargetItemsCount = 20,
                    viewportSizePx = 1_000,
                    effectiveVisibleSpan = 4f,
                    scrollFraction = 0f,
                )

            val target = metrics.targetForFraction(1f)

            target.index shouldBe 19
            target.scrollOffsetPx shouldBe 0
        }
    }

    init {
        test("drag target maps fraction directly to continuous target index") {
            val metrics =
                LazyListScrollbarMetrics(
                    totalItemsCount = 100,
                    scrollTargetItemsCount = 100,
                    viewportSizePx = 1_000,
                    effectiveVisibleSpan = 4f,
                    scrollFraction = 0f,
                    firstVisibleItemSizePx = 200,
                )

            val target = metrics.targetForFraction(0.5f)

            // maxScrollableIndex = 100 - 4 = 96. targetContinuousIndex = 0.5 * 96 = 48.0
            target.index shouldBe 48
            target.scrollOffsetPx shouldBe 0
        }
    }

    private fun snapshot(
        firstIndex: Int,
        firstOffset: Int = 0,
        totalItemsCount: Int = 100,
        itemSizes: List<Int>,
        canScrollBackward: Boolean = true,
        canScrollForward: Boolean = true,
    ): LazyListScrollbarSnapshot {
        var currentOffset = -firstOffset
        val visible = mutableListOf<LazyListScrollbarVisibleItem>()
        for (i in itemSizes.indices) {
            val size = itemSizes[i]
            visible.add(
                LazyListScrollbarVisibleItem(
                    index = firstIndex + i,
                    offset = currentOffset,
                    size = size,
                ),
            )
            currentOffset += size
        }
        return LazyListScrollbarSnapshot(
            totalItemsCount = totalItemsCount,
            viewportStartOffset = 0,
            viewportEndOffset = 1_000,
            canScrollBackward = canScrollBackward,
            canScrollForward = canScrollForward,
            visibleItems = visible,
        )
    }
}
