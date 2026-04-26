package com.lomo.app.feature.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePreloadGateTest {
    @Test
    fun `selectUrlsToEnqueue applies throttle and dedupe window`() {
        var nowMs = 1_000L
        val gate =
            ImagePreloadGate(
                eventThrottleMs = 100L,
                dedupeWindowMs = 1_000L,
                nowMs = { nowMs },
            )

        val firstBatch = gate.selectUrlsToEnqueue(listOf("a", "a", "b", " "))
        assertEquals(listOf("a", "b"), firstBatch)

        nowMs += 50L
        val throttledBatch = gate.selectUrlsToEnqueue(listOf("c"))
        assertTrue(throttledBatch.isEmpty())

        nowMs += 60L
        val dedupedBatch = gate.selectUrlsToEnqueue(listOf("a", "b"))
        assertTrue(dedupedBatch.isEmpty())

        nowMs += 1_000L
        val afterWindowBatch = gate.selectUrlsToEnqueue(listOf("a"))
        assertEquals(listOf("a"), afterWindowBatch)
    }

    @Test
    fun `snapshot restore keeps dedupe history across gate recreation`() {
        var nowMs = 1_000L
        val original =
            ImagePreloadGate(
                eventThrottleMs = 0L,
                dedupeWindowMs = 1_000L,
                nowMs = { nowMs },
            )

        assertEquals(listOf("cover-1"), original.selectUrlsToEnqueue(listOf("cover-1")))

        val restored =
            ImagePreloadGate(
                eventThrottleMs = 0L,
                dedupeWindowMs = 1_000L,
                nowMs = { nowMs },
            )
        restored.restore(original.snapshot())

        assertTrue(restored.selectUrlsToEnqueue(listOf("cover-1")).isEmpty())

        nowMs += 1_000L
        assertEquals(listOf("cover-1"), restored.selectUrlsToEnqueue(listOf("cover-1")))
    }
}
