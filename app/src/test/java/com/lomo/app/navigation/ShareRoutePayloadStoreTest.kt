/*
 * Behavior Contract:
 * - Unit under test: ShareRoutePayloadStoreTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for ShareRoutePayloadStoreTest.
 * - Boundary: boundary and edge cases for ShareRoutePayloadStoreTest.
 * - Failure: failure and error scenarios for ShareRoutePayloadStoreTest.
 * - Must-not-happen: invariants are never violated for ShareRoutePayloadStoreTest.
 *
 * - Behavior focus: test behavioral outcomes of ShareRoutePayloadStoreTest.
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
