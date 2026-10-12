package com.lomo.app.feature.gallery

import com.lomo.app.feature.image.FeedImagePreloadSize
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toImmutableList

/*
 * Behavior Contract:
 * - Unit under test: GalleryImagePreheatPlanner.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: bound gallery image preheating and window aspect resolution to the visible mosaic
 *   bands plus a fixed lookahead, with a hard cap on concurrent bitmap requests.
 *
 * Scenarios:
 * - Given a layout wider than the viewport, when a viewport window is planned, then only the
 *   visible bands plus the lookahead bands contribute urls, in stable tile order.
 * - Given a window larger than the active-request bound, when planned, then the request list is
 *   capped and extra in-window urls do not start.
 * - Given the window slides, when the next plan is created, then urls that left the window are
 *   returned for cancellation and unchanged urls are not restarted.
 * - Given the tile size changes, when the next plan is created, then size-stale urls are
 *   re-requested and cancelled.
 * - Given planned requests, when inspected, then every request carries the explicit tile size.
 *
 * Observable outcomes:
 * - planned request urls, the cancellation set, the aspect window urls, and request sizes.
 *
 * TDD proof:
 * - RED before implementation: GalleryImagePreheatPlanner did not exist; the gallery resolved
 *   aspects for every loaded item and never preheated by viewport.
 *
 * Excludes:
 * - Coil request execution, bounds decoding, LazyColumn composition, and the entry gate
 *   (GalleryScreenDisplayStateTest owns display-state gating).
 */
class GalleryImagePreheatPlannerTest : AppFunSpec() {
    init {
        test("given a deep layout when a viewport window is planned then only visible plus lookahead bands contribute") {
            val tileSize = FeedImagePreloadSize(widthPx = 540, heightPx = 540)
            val planner = GalleryImagePreheatPlanner(lookaheadBandCount = 1, startupBandCount = 1, maxActiveRequests = 24)
            val bands = (0 until 10).map { index -> band("b$index", "u-$index-a", "u-$index-b") }

            val plan =
                planner.planViewport(
                    bands = bands,
                    firstVisibleBand = 3,
                    visibleBandCount = 2,
                    tileSize = tileSize,
                )

            plan.windowUrls shouldContainExactly
                listOf("u-3-a", "u-3-b", "u-4-a", "u-4-b", "u-5-a", "u-5-b")
            plan.startRequests.map { request -> request.url } shouldContainExactly plan.windowUrls
            plan.cancelUrls shouldBe emptySet()
        }

        test("given more in-window urls than the bound when planned then requests are capped") {
            val tileSize = FeedImagePreloadSize(widthPx = 540, heightPx = 540)
            val planner = GalleryImagePreheatPlanner(lookaheadBandCount = 4, startupBandCount = 1, maxActiveRequests = 3)
            val bands = (0 until 6).map { index -> band("b$index", "u-$index-a", "u-$index-b") }

            val plan =
                planner.planViewport(
                    bands = bands,
                    firstVisibleBand = 0,
                    visibleBandCount = 2,
                    tileSize = tileSize,
                )

            plan.startRequests.map { request -> request.url } shouldContainExactly
                listOf("u-0-a", "u-0-b", "u-1-a")
            plan.windowUrls.size shouldBe 3
        }

        test("given the window slides when replanned then stale urls cancel and kept urls do not restart") {
            val tileSize = FeedImagePreloadSize(widthPx = 540, heightPx = 540)
            val planner = GalleryImagePreheatPlanner(lookaheadBandCount = 1, startupBandCount = 1, maxActiveRequests = 24)
            val bands = (0 until 10).map { index -> band("b$index", "u-$index") }

            planner.planViewport(bands = bands, firstVisibleBand = 0, visibleBandCount = 2, tileSize = tileSize)
            val plan =
                planner.planViewport(
                    bands = bands,
                    firstVisibleBand = 2,
                    visibleBandCount = 2,
                    tileSize = tileSize,
                )

            plan.cancelUrls shouldBe setOf("u-0", "u-1")
            plan.startRequests.map { request -> request.url } shouldContainExactly listOf("u-3", "u-4")
            plan.windowUrls shouldContainExactly listOf("u-2", "u-3", "u-4")
        }

        test("given the tile size changes when replanned then size-stale urls are re-requested") {
            val planner = GalleryImagePreheatPlanner(lookaheadBandCount = 1, startupBandCount = 1, maxActiveRequests = 24)
            val bands = listOf(band("b0", "u-0", "u-1"))
            val oldSize = FeedImagePreloadSize(widthPx = 360, heightPx = 360)
            val newSize = FeedImagePreloadSize(widthPx = 720, heightPx = 720)

            planner.planViewport(bands = bands, firstVisibleBand = 0, visibleBandCount = 1, tileSize = oldSize)
            val plan =
                planner.planViewport(bands = bands, firstVisibleBand = 0, visibleBandCount = 1, tileSize = newSize)

            plan.cancelUrls shouldBe setOf("u-0", "u-1")
            plan.startRequests.map { request -> request.url } shouldContainExactly listOf("u-0", "u-1")
            plan.startRequests.map { request -> request.size }.distinct() shouldBe listOf(newSize)
        }

        test("given an unsettled viewport when planned then the startup window applies") {
            val tileSize = FeedImagePreloadSize(widthPx = 540, heightPx = 540)
            val planner = GalleryImagePreheatPlanner(lookaheadBandCount = 1, startupBandCount = 2, maxActiveRequests = 24)
            val bands = (0 until 6).map { index -> band("b$index", "u-$index") }

            val plan = planner.planStartup(bands = bands, tileSize = tileSize)

            plan.startRequests.map { request -> request.url } shouldContainExactly listOf("u-0", "u-1")
        }
    }

    private fun band(
        key: String,
        vararg urls: String,
    ): GalleryMosaicLayout =
        GalleryMosaicLayout(
            tiles =
                urls
                    .mapIndexed { index, url ->
                        GalleryMosaicTile(
                            memoId = "$key-$index",
                            firstImageUrl = url,
                            aspectKind = GalleryAspectKind.Square,
                            aspectRatio = 1f,
                            column = index,
                            row = 0,
                            columnSpan = 4,
                            rowSpan = 4,
                        )
                    }.toImmutableList(),
            rowCount = 4,
        )
}
