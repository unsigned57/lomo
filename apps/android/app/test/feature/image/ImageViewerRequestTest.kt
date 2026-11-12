package com.lomo.app.feature.image

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.imageMemoFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: memo image navigation retains clicked image and its tag-bearing snapshot (P1).
 * Scenarios:
 * Given a memo image, when opened, then its index and originating memo are retained; a foreign image rejects.
 * Production scope: apps/android/app/src/feature/image/ImageViewerRequest.kt
 * TDD proof: media_navigation RED could not open the viewer from search; GREEN device journey
 * plus this request contract prevents detached memo metadata and invalid indices.
 * Test Change Justification:
 * Reason category: canonical memo-backed image request.
 * Old behavior/assertion being replaced: unknown image silently selected the first image.
 * Why old assertion is no longer correct: an image click must identify an actual image of its memo.
 * Coverage preserved by: known clicked index, original URL list and missing-image rejection.
 * Why this is not fitting the test to the implementation: asserts the requested image identity
 * and its source snapshot instead of whichever image the viewer happens to select.
 * Excludes: rendering and native media resolution.
 * Observable outcomes: clicked image index, original memo identity, image URLs and invalid-request rejection.
 */
class ImageViewerRequestTest : AppFunSpec() {
    init {
        test("given a memo image when opened then clicked index and metadata travel together") {
            val memo = imageMemoFixture(listOf("a.jpg", "b.jpg", "c.jpg"))
            val request = createImageViewerRequest(memo, "b.jpg")
            request.imageUrls shouldBe listOf("a.jpg", "b.jpg", "c.jpg")
            request.initialIndex shouldBe 1
            request.memo shouldBe memo
        }
        test("given an image absent from the memo when opened then the invalid request rejects") {
            shouldThrow<IllegalArgumentException> { createImageViewerRequest(imageMemoFixture(), "missing.jpg") }
        }
    }
}
