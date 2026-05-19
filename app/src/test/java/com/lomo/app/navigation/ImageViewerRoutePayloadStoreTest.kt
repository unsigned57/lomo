/*
 * Behavior Contract:
 * - Unit under test: ImageViewerRoutePayloadStoreTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for ImageViewerRoutePayloadStoreTest.
 * - Boundary: boundary and edge cases for ImageViewerRoutePayloadStoreTest.
 * - Failure: failure and error scenarios for ImageViewerRoutePayloadStoreTest.
 * - Must-not-happen: invariants are never violated for ImageViewerRoutePayloadStoreTest.
 *
 * - Behavior focus: test behavioral outcomes of ImageViewerRoutePayloadStoreTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.navigation

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


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
