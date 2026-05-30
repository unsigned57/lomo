/*
 * Behavior Contract:
 * - Unit under test: LAN share active-discovery target policy.
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: build deterministic active-discovery probe targets without
 *   allowing one scan to probe an entire IPv4 /24 subnet.
 *
 * Scenarios:
 * - Given a hotspot host, when active-discovery targets are built, then the scan
 *   keeps stable share-port targets inside the per-scan budget and excludes self.
 * - Given an ordinary Wi-Fi host, when active-discovery targets are built, then
 *   priority hosts are kept while the target list is capped.
 * - Given a non-IPv4 or non-private bind host, when targets are built, then no
 *   active probe targets are returned.
 *
 * Observable outcomes:
 * - returned target count, target host/port membership, and self exclusion.
 *
 * TDD proof:
 * - RED before the fix because `buildLanShareActiveDiscoveryTargets` returns the
 *   whole usable /24 minus self, producing 253 targets instead of the budgeted 64.
 *
 * Excludes:
 * - live sockets, HTTP timeout behavior, Android ConnectivityManager callbacks,
 *   and active scan retry cadence.
 *
 * Test Change Justification:
 * - Reason category: behavior contract correction for active-discovery budget.
 * - Old behavior/assertion being replaced: target tests accepted complete /24
 *   target lists including far subnet endpoints in every scan.
 * - Why old assertion is no longer correct: full-subnet probes violate the
 *   power/network budget requirement.
 * - Coverage preserved by: assertions still lock hotspot/Wi-Fi priority targets,
 *   stable port selection, private-host filtering, and self exclusion.
 * - Why this is not fitting the test to the implementation: the new cap comes
 *   from the audit requirement, not from the implementation shape.
 */
package com.lomo.data.share

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class LanShareActiveDiscoveryTargetPolicyTest : DataFunSpec() {
    init {
        test("hotspot host scans clients on the stable lan share port") { `hotspot host scans clients on the stable lan share port`() }

        test("ordinary wifi host budgets same subnet scan while keeping priority targets") {
            `ordinary wifi host budgets same subnet scan while keeping priority targets`()
        }

        test("active discovery does not scan non ipv4 bind hosts") { `active discovery does not scan non ipv4 bind hosts`() }
    }


    private fun `hotspot host scans clients on the stable lan share port`() {
        val targets = buildLanShareActiveDiscoveryTargets(bindHost = "192.168.43.1")

        targets.size shouldBe EXPECTED_ACTIVE_DISCOVERY_TARGET_BUDGET
        (targets.contains(LanShareActiveDiscoveryTarget("192.168.43.2", LAN_SHARE_DISCOVERY_PORT))).shouldBeTrue()
        (targets.contains(LanShareActiveDiscoveryTarget("192.168.43.254", LAN_SHARE_DISCOVERY_PORT))).shouldBeTrue()
        (targets.any { it.host == "192.168.43.1" }).shouldBeFalse()
    }

    private fun `ordinary wifi host budgets same subnet scan while keeping priority targets`() {
        val targets = buildLanShareActiveDiscoveryTargets(bindHost = "192.168.1.37")

        targets.size shouldBe EXPECTED_ACTIVE_DISCOVERY_TARGET_BUDGET
        targets.first().port shouldBe LAN_SHARE_DISCOVERY_PORT
        (targets.contains(LanShareActiveDiscoveryTarget("192.168.1.1", LAN_SHARE_DISCOVERY_PORT))).shouldBeTrue()
        (targets.contains(LanShareActiveDiscoveryTarget("192.168.1.254", LAN_SHARE_DISCOVERY_PORT))).shouldBeTrue()
        (targets.contains(LanShareActiveDiscoveryTarget("192.168.1.38", LAN_SHARE_DISCOVERY_PORT))).shouldBeTrue()
        (targets.any { it.host == "192.168.1.37" }).shouldBeFalse()
    }

    private fun `active discovery does not scan non ipv4 bind hosts`() {
        buildLanShareActiveDiscoveryTargets("fd00::24") shouldBe emptyList<LanShareActiveDiscoveryTarget>()
        buildLanShareActiveDiscoveryTargets("localhost") shouldBe emptyList<LanShareActiveDiscoveryTarget>()
    }
}

private const val EXPECTED_ACTIVE_DISCOVERY_TARGET_BUDGET = 64
