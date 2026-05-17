package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: ImagePreloadGate.
 * - Behavior focus: memo image preload should dedupe repeated URLs while allowing visible-range return preloads to bypass event-level throttling.
 * - Observable outcomes: selected preload URL batches under normal, restored, and priority-visible requests.
 * - Red phase: Priority visible preload fails before the fix because ImagePreloadGate only exposes throttled selection, so startup preloads can suppress immediately visible memo images after returning to the main list.
 * - Excludes: Coil request execution, disk or memory cache hit rates, LazyColumn measurement timing.
 *
 * Test Change Justification:
 * - Reason category: Companion behavior added.
 * - Old behavior/assertion being replaced: none; existing throttle and restore tests remain unchanged.
 * - Why old assertion is no longer correct: not applicable.
 * - Coverage preserved by: existing tests still cover default throttling and dedupe restoration.
 * - Why this is not fitting the test to the implementation: the new case defines the user-visible return-to-list preload priority before production edits.
 */
class ImagePreloadGateTest : AppFunSpec() {
    init {
        test("selectUrlsToEnqueue applies throttle and dedupe window") {
            var nowMs = 1_000L
            val gate =
                ImagePreloadGate(
                    eventThrottleMs = 100L,
                    dedupeWindowMs = 1_000L,
                    nowMs = { nowMs },
                )

            val firstBatch = gate.selectUrlsToEnqueue(listOf("a", "a", "b", " "))
            (firstBatch) shouldBe (listOf("a", "b"))

            nowMs += 50L
            val throttledBatch = gate.selectUrlsToEnqueue(listOf("c"))
            ((throttledBatch.isEmpty())) shouldBe true

            nowMs += 60L
            val dedupedBatch = gate.selectUrlsToEnqueue(listOf("a", "b"))
            ((dedupedBatch.isEmpty())) shouldBe true

            nowMs += 1_000L
            val afterWindowBatch = gate.selectUrlsToEnqueue(listOf("a"))
            (afterWindowBatch) shouldBe (listOf("a"))
        }
    }

    init {
        test("snapshot restore keeps dedupe history across gate recreation") {
            var nowMs = 1_000L
            val original =
                ImagePreloadGate(
                    eventThrottleMs = 0L,
                    dedupeWindowMs = 1_000L,
                    nowMs = { nowMs },
                )

            (original.selectUrlsToEnqueue(listOf("cover-1"))) shouldBe (listOf("cover-1"))

            val restored =
                ImagePreloadGate(
                    eventThrottleMs = 0L,
                    dedupeWindowMs = 1_000L,
                    nowMs = { nowMs },
                )
            restored.restore(original.snapshot())

            ((restored.selectUrlsToEnqueue(listOf("cover-1")).isEmpty())) shouldBe true

            nowMs += 1_000L
            (restored.selectUrlsToEnqueue(listOf("cover-1"))) shouldBe (listOf("cover-1"))
        }
    }

    init {
        test("priority selection bypasses event throttle while keeping url dedupe") {
            var nowMs = 1_000L
            val gate =
                ImagePreloadGate(
                    eventThrottleMs = 150L,
                    dedupeWindowMs = 1_000L,
                    nowMs = { nowMs },
                )

            (gate.selectUrlsToEnqueue(listOf("startup-cover"))) shouldBe (listOf("startup-cover"))

            nowMs += 50L
            (gate.selectPriorityUrlsToEnqueue(listOf("visible-cover"))) shouldBe (listOf("visible-cover"))
            ((gate.selectPriorityUrlsToEnqueue(listOf("visible-cover")).isEmpty())) shouldBe true
        }
    }

}
