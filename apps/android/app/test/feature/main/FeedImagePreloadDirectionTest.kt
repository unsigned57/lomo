package com.lomo.app.feature.main

import com.lomo.app.feature.image.FeedImagePreloadSize
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.emptyRenderDocument
import com.lomo.domain.model.Memo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/*
 * Behavior Contract:
 * Capability: bounded image prefetch follows viewport travel; owner: app; P1.
 * Scenarios:
 * Given forward travel, when scrolling reverses, then visible images precede backward lookahead
 * and obsolete forward requests are cancelled. Given invalid viewport indices, reject them.
 * Observable outcomes: ordered requests, cancellation set and request budget.
 * TDD proof: RED/GREEN in target/lomo/reports/manual/startup-scroll-round2/red.log.
 * Excludes: Coil scheduling, bitmap decode duration and display frames.
 */
class FeedImagePreloadDirectionTest : AppFunSpec() {
    init {
        test("given invalid viewport coordinates when planning then they are rejected") {
            val planner = FeedImagePreloadPlanner()
            shouldThrow<IllegalArgumentException> {
                planner.planViewport(
                    FeedImagePreloadWindow(0, persistentListOf()),
                    -1,
                    1,
                    FeedImagePreloadSize(720, 405),
                )
            }
            shouldThrow<IllegalArgumentException> {
                planner.planViewport(
                    FeedImagePreloadWindow(0, persistentListOf()),
                    Int.MAX_VALUE,
                    1,
                    FeedImagePreloadSize(720, 405),
                )
            }
        }

        test("given a forward window when scrolling reverses then prefetch follows backwards within its bound") {
            val planner = FeedImagePreloadPlanner(lookaheadMemoCount = 2, startupMemoCount = 1, maxActiveRequests = 3)
            val window = window()
            val size = FeedImagePreloadSize(720, 405)
            planner.planViewport(window, 5, 1, size)
            planner.planViewport(window, 8, 1, size)
            val reverse = planner.planViewport(window, 7, 1, size)

            reverse.startRequests.map { it.url } shouldBe listOf("image-7", "image-6", "image-5")
            reverse.cancelUrls shouldBe setOf("image-8", "image-9", "image-10")
            reverse.startRequests.size shouldBe 3
        }

        test("given a fast viewport jump when planning then lookahead expands without exceeding the request bound") {
            val planner = FeedImagePreloadPlanner(lookaheadMemoCount = 1, startupMemoCount = 1, maxActiveRequests = 4)
            val window = window()
            val size = FeedImagePreloadSize(720, 405)
            planner.planViewport(window, 0, 1, size)
            val jump = planner.planViewport(window, 4, 1, size)
            jump.startRequests.map { it.url } shouldBe listOf("image-4", "image-5", "image-6", "image-7")
            jump.cancelUrls shouldBe setOf("image-0", "image-1")
        }

        test("given backward travel when a stationary viewport changes size then direction is retained until reset") {
            val planner = FeedImagePreloadPlanner(lookaheadMemoCount = 2, startupMemoCount = 1, maxActiveRequests = 3)
            val window = window()
            val size = FeedImagePreloadSize(720, 405)
            planner.planViewport(window, 6, 1, size)
            planner.planViewport(window, 5, 1, size)
            val resized = planner.planViewport(window, 5, 1, FeedImagePreloadSize(1080, 608))
            resized.startRequests.map { it.url } shouldBe listOf("image-5", "image-4", "image-3")
            planner.reset()
            planner.planViewport(
                window,
                5,
                1,
                size,
            ).startRequests.map { it.url } shouldBe listOf("image-5", "image-6", "image-7")
        }
    }

    private fun window(): FeedImagePreloadWindow = FeedImagePreloadWindow(
        0,
        (0..12).map { index ->
            val memo =
                Memo(id = "m$index", timestamp = 1L, content = "body", rawContent = "body", dateKey = "2026_10_28")
            MemoUiModel(
                memo,
                "body",
                emptyRenderDocument("body"),
                tags = persistentListOf(),
                imageUrls = persistentListOf("image-$index"),
                shouldShowExpand = false,
            )
        }.toImmutableList(),
    )
}
