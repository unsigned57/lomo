package com.lomo.app.feature.main

import com.lomo.app.feature.image.FeedImagePreloadSize
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/*
 * Behavior Contract:
 * - Unit under test: FeedImagePreloadPlanner.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: bound home-feed markdown image preloads to the current list lifecycle window.
 *
 * Scenarios:
 * - Given a viewport with many image URLs, when the feed planner creates preload work,
 *   then only the configured visible/lookahead budget is selected in stable row order.
 * - Given Paging has loaded an offscreen window after placeholders, when visible rows
 *   are planned from LazyColumn absolute indices, then the matching loaded rows are selected.
 * - Given Paging has loaded an offscreen window after placeholders, when lookahead rows
 *   are planned, then lookahead starts from the absolute visible end instead of the loaded
 *   window's offsetless list index.
 * - Given a fast scroll moves the active window, when the next plan is created,
 *   then stale URLs from the old window are returned for cancellation.
 * - Given the feed planner emits preload work, when requests are selected,
 *   then every request carries the explicit feed-rendered markdown image size policy.
 *
 * Observable outcomes:
 * - planned request URLs, cancellation URL set, and request size values.
 *
 * TDD proof:
 * - RED before paging-offset repair: absolute loaded-window scenarios do not compile because
 *   `FeedImagePreloadWindow` does not exist and the planner only accepts offsetless memo lists.
 *
 * Excludes:
 * - Coil request execution, bitmap decoding, LazyColumn frame timing, and memo height-change animation.
 */
class FeedImagePreloadPlannerTest : AppFunSpec() {
    init {
        test("given many images when planning viewport then request count is budgeted in row order") {
            val preloadSize = FeedImagePreloadSize(widthPx = 720, heightPx = 405)
            val planner =
                FeedImagePreloadPlanner(
                    lookaheadMemoCount = 2,
                    startupMemoCount = 3,
                    maxActiveRequests = 4,
                )

            val plan =
                planner.planViewport(
                    loadedWindow =
                        FeedImagePreloadWindow(
                            placeholdersBefore = 0,
                            memos =
                                listOf(
                                    memoUiModel("memo-0", "m0-a"),
                                    memoUiModel("memo-1", "m1-a", "m1-b", "m1-c"),
                                    memoUiModel("memo-2", "m2-a", "m2-b"),
                                    memoUiModel("memo-3", "m3-a"),
                                    memoUiModel("memo-4", "m4-a"),
                                ).toImmutableList(),
                        ),
                    firstVisible = 1,
                    visibleCount = 1,
                    preloadSize = preloadSize,
                )

            plan.startRequests.map { request -> request.url } shouldContainExactly
                listOf("m1-a", "m1-b", "m1-c", "m2-a")
            plan.startRequests.map { request -> request.size }.distinct() shouldBe listOf(preloadSize)
            plan.cancelUrls shouldBe emptySet()
        }

        test("given loaded page after placeholders when visible rows are planned then absolute rows are selected") {
            val preloadSize = FeedImagePreloadSize(widthPx = 720, heightPx = 405)
            val planner =
                FeedImagePreloadPlanner(
                    lookaheadMemoCount = 0,
                    startupMemoCount = 2,
                    maxActiveRequests = 4,
                )
            val loadedWindow =
                FeedImagePreloadWindow(
                    placeholdersBefore = 40,
                    memos =
                        listOf(
                            memoUiModel("memo-40", "loaded-40"),
                            memoUiModel("memo-41", "loaded-41"),
                            memoUiModel("memo-42", "loaded-42"),
                        ).toImmutableList(),
                )

            val plan =
                planner.planViewport(
                    loadedWindow = loadedWindow,
                    firstVisible = 41,
                    visibleCount = 1,
                    preloadSize = preloadSize,
                )

            plan.startRequests.map { request -> request.url } shouldContainExactly listOf("loaded-41")
            plan.cancelUrls shouldBe emptySet()
        }

        test("given loaded page after placeholders when lookahead is planned then following rows are selected") {
            val preloadSize = FeedImagePreloadSize(widthPx = 720, heightPx = 405)
            val planner =
                FeedImagePreloadPlanner(
                    lookaheadMemoCount = 2,
                    startupMemoCount = 2,
                    maxActiveRequests = 4,
                )
            val loadedWindow =
                FeedImagePreloadWindow(
                    placeholdersBefore = 40,
                    memos =
                        listOf(
                            memoUiModel("memo-40", "loaded-40"),
                            memoUiModel("memo-41", "loaded-41"),
                            memoUiModel("memo-42", "loaded-42"),
                            memoUiModel("memo-43", "loaded-43"),
                        ).toImmutableList(),
                )

            val plan =
                planner.planViewport(
                    loadedWindow = loadedWindow,
                    firstVisible = 41,
                    visibleCount = 1,
                    preloadSize = preloadSize,
                )

            plan.startRequests.map { request -> request.url } shouldContainExactly
                listOf("loaded-41", "loaded-42", "loaded-43")
        }

        test("given active image work when fast scroll changes the window then stale urls are cancelled") {
            val preloadSize = FeedImagePreloadSize(widthPx = 720, heightPx = 405)
            val planner =
                FeedImagePreloadPlanner(
                    lookaheadMemoCount = 1,
                    startupMemoCount = 2,
                    maxActiveRequests = 3,
                )
            val memos =
                listOf(
                    memoUiModel("memo-0", "m0-a", "m0-b"),
                    memoUiModel("memo-1", "m1-a"),
                    memoUiModel("memo-2", "m2-a"),
                    memoUiModel("memo-3", "m3-a"),
                    memoUiModel("memo-4", "m4-a"),
                ).toImmutableList()

            val firstPlan =
                planner.planViewport(
                    loadedWindow = FeedImagePreloadWindow(placeholdersBefore = 0, memos = memos),
                    firstVisible = 0,
                    visibleCount = 1,
                    preloadSize = preloadSize,
                )
            val secondPlan =
                planner.planViewport(
                    loadedWindow = FeedImagePreloadWindow(placeholdersBefore = 0, memos = memos),
                    firstVisible = 3,
                    visibleCount = 1,
                    preloadSize = preloadSize,
                )

            firstPlan.startRequests.map { request -> request.url } shouldContainExactly listOf("m0-a", "m0-b", "m1-a")
            secondPlan.cancelUrls shouldBe setOf("m0-a", "m0-b", "m1-a")
            secondPlan.startRequests.map { request -> request.url } shouldContainExactly listOf("m3-a", "m4-a")
        }

        test("given startup before viewport signals when planning then first memo slice uses the same budget owner") {
            val preloadSize = FeedImagePreloadSize(widthPx = 720, heightPx = 405)
            val planner =
                FeedImagePreloadPlanner(
                    lookaheadMemoCount = 5,
                    startupMemoCount = 2,
                    maxActiveRequests = 3,
                )

            val plan =
                planner.planStartup(
                    loadedWindow =
                        FeedImagePreloadWindow(
                            placeholdersBefore = 0,
                            memos =
                                listOf(
                                    memoUiModel("memo-1", "cover-1", "cover-2"),
                                    memoUiModel("memo-2", "cover-3"),
                                    memoUiModel("memo-3", "cover-4"),
                                ).toImmutableList(),
                        ),
                    preloadSize = preloadSize,
                )

            plan.startRequests.map { request -> request.url } shouldContainExactly
                listOf("cover-1", "cover-2", "cover-3")
            plan.startRequests.map { request -> request.size }.distinct() shouldBe listOf(preloadSize)
        }

        test("given active urls when feed preload size changes then stale sized requests are restarted") {
            val initialSize = FeedImagePreloadSize(widthPx = 96, heightPx = 54)
            val settledSize = FeedImagePreloadSize(widthPx = 720, heightPx = 405)
            val planner =
                FeedImagePreloadPlanner(
                    lookaheadMemoCount = 1,
                    startupMemoCount = 2,
                    maxActiveRequests = 2,
                )
            val memos =
                listOf(
                    memoUiModel("memo-1", "cover-1"),
                    memoUiModel("memo-2", "cover-2"),
                ).toImmutableList()

            planner.planViewport(
                loadedWindow = FeedImagePreloadWindow(placeholdersBefore = 0, memos = memos),
                firstVisible = 0,
                visibleCount = 1,
                preloadSize = initialSize,
            )
            val settledPlan =
                planner.planViewport(
                    loadedWindow = FeedImagePreloadWindow(placeholdersBefore = 0, memos = memos),
                    firstVisible = 0,
                    visibleCount = 1,
                    preloadSize = settledSize,
                )

            settledPlan.cancelUrls shouldBe setOf("cover-1", "cover-2")
            settledPlan.startRequests.map { request -> request.url } shouldContainExactly listOf("cover-1", "cover-2")
            settledPlan.startRequests.map { request -> request.size }.distinct() shouldBe listOf(settledSize)
        }
    }

    private fun memoUiModel(
        id: String,
        vararg imageUrls: String,
    ): MemoUiModel =
        MemoUiModel(
            memo =
                Memo(
                    id = id,
                    timestamp = 1L,
                    content = id,
                    rawContent = id,
                    dateKey = "2026_04_02",
                ),
            processedContent = id,
            precomputedRenderPlan = null,
            tags = persistentListOf(),
            imageUrls = persistentListOf(*imageUrls),
        )
}
