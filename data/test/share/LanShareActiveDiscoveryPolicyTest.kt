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
 * - Given empty scans, when scan results are recorded, then retry delay backs off from the legacy one-second loop.
 * - Given a discovered peer, when the result is recorded, then follow-up scans use a low-frequency cadence.
 * - Given a stopped session with prior backoff, when a new policy session is created, then the next scan is prompt again.
 *
 * Observable outcomes:
 * - returned scan delay values.
 *
 * TDD proof:
 * - RED observed with `./kotlin test --include-classes='com.lomo.data.share.LanShareActiveDiscoveryPolicyTest'`.
 * - Before Batch 2A, policy creation/usage was missing and lifecycle retries occurred after the legacy 1,000 ms delay instead of returning 4,000 ms empty-scan backoff, 8,000 ms second backoff, and 30,000 ms discovered-peer cadence.
 *
 * Excludes:
 * - coroutine delay mechanics, NSD callbacks, live sockets, and active probe target construction.
 */
class LanShareActiveDiscoveryPolicyTest : DataFunSpec() {
    init {
        test("given new discovery session when policy is queried then first scan is prompt") {
            val policy = LanShareActiveDiscoverySchedulePolicy()

            policy.delayBeforeNextScanMs() shouldBe 0L
        }

        test("given empty scans when results are recorded then retry delay backs off") {
            val policy = LanShareActiveDiscoverySchedulePolicy()

            policy.recordScanResult(foundDeviceCount = 0)
            val firstBackoff = policy.delayBeforeNextScanMs()
            policy.recordScanResult(foundDeviceCount = 0)

            firstBackoff shouldBe 4_000L
            policy.delayBeforeNextScanMs() shouldBe 8_000L
        }

        test("given discovered peer when result is recorded then next scan uses low frequency cadence") {
            val policy = LanShareActiveDiscoverySchedulePolicy()

            policy.recordScanResult(foundDeviceCount = 1)

            policy.delayBeforeNextScanMs() shouldBe 30_000L
        }

        test("given prior backoff when a new policy session starts then next scan is prompt again") {
            val previousSession = LanShareActiveDiscoverySchedulePolicy()
            previousSession.recordScanResult(foundDeviceCount = 0)
            previousSession.delayBeforeNextScanMs() shouldBe 4_000L

            val newSession = LanShareActiveDiscoverySchedulePolicy()

            newSession.delayBeforeNextScanMs() shouldBe 0L
        }
    }
}
