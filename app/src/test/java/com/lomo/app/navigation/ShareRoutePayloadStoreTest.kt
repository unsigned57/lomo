/*
 * Test Contract:
 * - Unit under test: ShareRoutePayloadStoreTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for ShareRoutePayloadStoreTest.
 * - Boundary: boundary and edge cases for ShareRoutePayloadStoreTest.
 * - Failure: failure and error scenarios for ShareRoutePayloadStoreTest.
 * - Must-not-happen: invariants are never violated for ShareRoutePayloadStoreTest.
 *
 * - Behavior focus: test behavioral outcomes of ShareRoutePayloadStoreTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.navigation

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class ShareRoutePayloadStoreTest : AppFunSpec() {
    init {
        afterTest {
            ShareRoutePayloadStore.clearForTest()
        }
    }

    init {
        test("consumeMemoContent returns stored value once") {
            val key = ShareRoutePayloadStore.putMemoContent("memo body")

            (ShareRoutePayloadStore.consumeMemoContent(key)) shouldBe ("memo body")
            (ShareRoutePayloadStore.consumeMemoContent(key)) shouldBe null
        }
    }

    init {
        test("consumeMemoContent returns null for unknown key") {
            (ShareRoutePayloadStore.consumeMemoContent("missing")) shouldBe null
        }
    }

}
