package com.lomo.app.feature.image

/**
 * Behavior Contract:
 * - Unit under test: Lomo image request builders.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: build Coil requests with shared URL cache keys and explicit feed preload sizing.
 *
 * Scenarios:
 * - Given an image URL, when a shared-key request is built, then data, memory key,
 *   and placeholder memory key all use the URL.
 * - Given a home-feed preload spec, when its Coil request is built, then the request
 *   keeps the shared URL cache keys and carries the explicit rendered feed size.
 *
 * Observable outcomes:
 * - request cache keys, data, and size resolver output.
 *
 * TDD proof:
 * - RED before production fix: `FeedImagePreloadSize`, `ImagePreloadSpec`, and
 *   `createImagePreloadRequest` do not exist, so the feed-size scenario does not compile.
 *
 * Excludes:
 * - actual fetch, decode, memory-cache hit rate, and image rendering.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
 */


import android.content.Context
import coil3.size.Dimension
import coil3.size.Size
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

class LomoImageRequestTest : AppFunSpec() {
    init {
        test("lomoSharedKeyImageRequest uses the url as both memory and placeholder cache keys") {
            val url = "file:///lomo/sample.jpg"
            val context = mockk<Context>(relaxed = true)

            val request = lomoSharedKeyImageRequest(context = context, url = url)

            request.memoryCacheKey shouldBe url
            request.placeholderMemoryCacheKey?.key shouldBe url
            request.data shouldBe url
        }

        test("createImagePreloadRequest uses shared keys and explicit feed-rendered size") {
            runTest {
                val url = "file:///lomo/feed-sample.jpg"
                val context = mockk<Context>(relaxed = true)
                val preloadSpec =
                    ImagePreloadSpec(
                        url = url,
                        size = FeedImagePreloadSize(widthPx = 720, heightPx = 405),
                    )

                val request = createImagePreloadRequest(context = context, spec = preloadSpec)

                request.memoryCacheKey shouldBe url
                request.placeholderMemoryCacheKey?.key shouldBe url
                request.data shouldBe url
                request.sizeResolver.size() shouldBe
                    Size(
                        width = Dimension.Pixels(720),
                        height = Dimension.Pixels(405),
                    )
            }
        }
    }
}
