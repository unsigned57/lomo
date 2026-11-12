package com.lomo.app.navigation

import com.lomo.app.feature.image.createImageViewerRequest
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.imageMemoFixture
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: image-route payload retains memo metadata independently of gallery paging (P1).
 * Scenarios:
 * Given a memo absent from a gallery page, when cached navigation is read, then it retains images and tags
 * without consuming the snapshot. Missing/expired keys remain explicitly absent.
 * Production scope: apps/android/app/src/navigation/NavRoute.kt
 * TDD proof: search media journey RED; GREEN with memo-backed payload and device navigation.
 * Test Change Justification:
 * Reason category: image navigation carries its originating memo instead of only URLs.
 * Old behavior/assertion being replaced: URL-only cache access.
 * Why old assertion is no longer correct: URLs cannot supply tags for older search results.
 * Coverage preserved by: non-consuming repeat access and unknown-key absence, plus tag snapshot.
 * Why this is not fitting the test to the implementation: verifies the navigated memo identity,
 * images and tags regardless of which gallery rows are currently loaded.
 * Excludes: process-death cache expiry and device rendering.
 * Observable outcomes: repeatable cached memo reads with images and tags, and absence for unknown keys.
 */
class ImageViewerRoutePayloadStoreTest : AppFunSpec() {
    init {
        afterTest { ImageViewerRoutePayloadStore.clearForTest() }
        test("given a memo payload when read twice then its image and tags remain available") {
            val request = createImageViewerRequest(imageMemoFixture(), "b.jpg")
            val key = ImageViewerRoutePayloadStore.put(request)
            ImageViewerRoutePayloadStore.get(key) shouldBe request
            ImageViewerRoutePayloadStore.get(key)?.memo?.tags shouldBe request.memo.tags
        }
        test("given an unknown key when read then absence is explicit") {
            ImageViewerRoutePayloadStore.get("missing") shouldBe null
        }
    }
}
