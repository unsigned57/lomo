/*
 * Test Contract:
 * - Unit under test: ImageViewerRoutePayloadStoreTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for ImageViewerRoutePayloadStoreTest.
 * - Boundary: boundary and edge cases for ImageViewerRoutePayloadStoreTest.
 * - Failure: failure and error scenarios for ImageViewerRoutePayloadStoreTest.
 * - Must-not-happen: invariants are never violated for ImageViewerRoutePayloadStoreTest.
 *
 * - Behavior focus: test behavioral outcomes of ImageViewerRoutePayloadStoreTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.navigation

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class ImageViewerRoutePayloadStoreTest : AppFunSpec() {
    init {
        afterTest {
            ImageViewerRoutePayloadStore.clearForTest()
        }
    }

    init {
        test("getImageUrls returns stored value without consuming it") {
            val key = ImageViewerRoutePayloadStore.putImageUrls(listOf("a.jpg", "b.jpg"))

            (ImageViewerRoutePayloadStore.getImageUrls(key)) shouldBe (listOf("a.jpg", "b.jpg"))
            (ImageViewerRoutePayloadStore.getImageUrls(key)) shouldBe (listOf("a.jpg", "b.jpg"))
        }
    }

    init {
        test("getImageUrls returns null for unknown key") {
            (ImageViewerRoutePayloadStore.getImageUrls("missing")) shouldBe null
        }
    }

}
