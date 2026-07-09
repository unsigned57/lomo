/*
 * Test Contract:
 * - Unit under test: ImageViewerRequestTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for ImageViewerRequestTest.
 * - Boundary: boundary and edge cases for ImageViewerRequestTest.
 * - Failure: failure and error scenarios for ImageViewerRequestTest.
 * - Must-not-happen: invariants are never violated for ImageViewerRequestTest.
 *
 * - Behavior focus: test behavioral outcomes of ImageViewerRequestTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.feature.image

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class ImageViewerRequestTest : AppFunSpec() {
    init {
        test("createImageViewerRequest keeps clicked image index") {
            val request =
                createImageViewerRequest(
                    imageUrls = listOf("a.jpg", "b.jpg", "c.jpg"),
                    clickedUrl = "b.jpg",
                )

            (request.imageUrls) shouldBe (listOf("a.jpg", "b.jpg", "c.jpg"))
            (request.initialIndex) shouldBe (1)
        }
    }

    init {
        test("createImageViewerRequest falls back to first image when clicked url is missing") {
            val request =
                createImageViewerRequest(
                    imageUrls = listOf("a.jpg", "b.jpg"),
                    clickedUrl = "missing.jpg",
                )

            (request.imageUrls) shouldBe (listOf("a.jpg", "b.jpg"))
            (request.initialIndex) shouldBe (0)
        }
    }

}
