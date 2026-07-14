package com.lomo.data.share

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: com.lomo.data.share.LanShareActiveDiscoverySchedulePolicy.
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: choose active LAN discovery scan delays without embedding scheduling branches in the lifecycle loop.
 *
 * Scenarios:
 * - Given a new discovery session, when the next scan delay is requested, then the first scan is prompt.
 * - Given a new session, when the first four scans are empty, then all four subnet windows run without delay.
 * - Given later empty scans, when scan results are recorded, then retry delay backs off with a 15-second ceiling.
 * - Given a discovered peer, when the result is recorded, then follow-up scans use a low-frequency cadence.
 * - Given a stopped session with prior backoff, when a new policy session is created, then the next scan is prompt again.
 *
 * Observable outcomes:
 * - returned scan delay values.
 *
 * TDD proof:
 * - RED: before the fix, the policy waited 4 seconds immediately after the first empty 64-host window
 *   and grew to 60 seconds, so a /24 could take tens of seconds to cover.
 *
 * Excludes:
 * - coroutine delay mechanics, NSD callbacks, live sockets, and active probe target construction.
 *
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Old behavior/assertion being replaced: asserting empty scan backoff increases immediately to 4, 8 seconds.
 * - Why old assertion is no longer correct: to discover devices faster, we scan the first four subnet windows without delay.
 * - Coverage preserved by: asserting that the first four empty scans run with 0 delay, and subsequent scans backoff up to 15 seconds.
 * - Why this is not fitting the test to the implementation: it verifies the new optimized scanning cadence required to avoid discovery delays.
 */
class LanShareActiveDiscoveryPolicyTest : DataFunSpec() {
    init {
        test("given new discovery session when policy is queried then first scan is prompt") {
            val policy = LanShareActiveDiscoverySchedulePolicy()

            policy.delayBeforeNextScanMs() shouldBe 0L
        }

        test("given new session when first four scans are empty then subnet windows remain prompt") {
            val policy = LanShareActiveDiscoverySchedulePolicy()

            val delaysAfterEmptyScans =
                (1..4).map {
                    policy.recordScanResult(foundDeviceCount = 0)
                    policy.delayBeforeNextScanMs()
                }

            delaysAfterEmptyScans shouldBe listOf(0L, 0L, 0L, 4_000L)
        }

        test("given prompt coverage is complete when empty scans continue then backoff is capped at fifteen seconds") {
            val policy = LanShareActiveDiscoverySchedulePolicy()
            repeat(4) { policy.recordScanResult(foundDeviceCount = 0) }

            val backoffs =
                (1..4).map {
                    val current = policy.delayBeforeNextScanMs()
                    policy.recordScanResult(foundDeviceCount = 0)
                    current
                }

            backoffs shouldBe listOf(4_000L, 8_000L, 15_000L, 15_000L)
        }

        test("given discovered peer when result is recorded then next scan uses low frequency cadence") {
            val policy = LanShareActiveDiscoverySchedulePolicy()

            policy.recordScanResult(foundDeviceCount = 1)

            policy.delayBeforeNextScanMs() shouldBe 30_000L
        }

        test("given prior backoff when a new policy session starts then next scan is prompt again") {
            val previousSession = LanShareActiveDiscoverySchedulePolicy()
            repeat(4) { previousSession.recordScanResult(foundDeviceCount = 0) }
            previousSession.delayBeforeNextScanMs() shouldBe 4_000L

            val newSession = LanShareActiveDiscoverySchedulePolicy()

            newSession.delayBeforeNextScanMs() shouldBe 0L
        }
    }
}
