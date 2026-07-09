/*
 * Behavior Contract:
 * - Unit under test: SynchronizedLruStore
 *
 * Scenarios:
 * - Happy: standard happy path for SynchronizedLruStoreTest.
 * - Boundary: boundary and edge cases for SynchronizedLruStoreTest.
 * - Failure: failure and error scenarios for SynchronizedLruStoreTest.
 * - Must-not-happen: invariants are never violated for SynchronizedLruStoreTest.
 * - Behavior focus: shared in-memory caches must keep LRU access semantics while remaining safe to snapshot and clear from synchronized callers.
 * - Observable outcomes: eviction order after access, stored values from snapshot, and full clear behavior.
 * - TDD proof: Fails before the fix because the shared LRU helper does not exist, leaving each cache site to hand-roll LinkedHashMap eviction separately.
 * - Excludes: navigation payload TTL rules, bitmap decoding, and Compose rendering.
 */

package com.lomo.ui.util

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


import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

class SynchronizedLruStoreTest : UiComponentsFunSpec() {
    init {
        test("get promotes entry so next insert evicts the least recently used key") {
            val store = SynchronizedLruStore<String, String>(maxEntries = 2)

            store.put("first", "a")
            store.put("second", "b")
            store.get("first") shouldBe "a"

            store.put("third", "c")

            store.get("first") shouldBe "a"
            store.get("second") shouldBe null
            store.get("third") shouldBe "c"
        }
    }

    init {
        test("snapshot and clear expose current contents without retaining stale entries") {
            val store = SynchronizedLruStore<String, Int>(maxEntries = 3)

            store.put("one", 1)
            store.put("two", 2)

            store.snapshot() shouldBe mapOf("one" to 1, "two" to 2)

            store.clear()

            store.snapshot() shouldBe emptyMap<String, Int>()
            store.get("one") shouldBe null
        }
    }
}
