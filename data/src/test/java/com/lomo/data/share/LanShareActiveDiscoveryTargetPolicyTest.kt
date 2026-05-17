package com.lomo.data.share


import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Test Contract:
 * - Unit under test: LAN share active-discovery target policy.
 * - Behavior focus: when platform NSD does not surface peers on hotspot or same-Wi-Fi networks,
 *   the fallback discovery must probe the local IPv4 subnet on Lomo's stable share port.
 * - Observable outcomes: deterministic probe targets for hotspot and ordinary Wi-Fi bind hosts.
 * - Red phase: Fails before the fix because LAN share only depends on NSD and the random
 *   server port, leaving no deterministic target list for hotspot fallback discovery.
 * - Excludes: real sockets, HTTP timeout behavior, and platform ConnectivityManager callbacks.
 */
class LanShareActiveDiscoveryTargetPolicyTest : DataFunSpec() {
    init {
        test("hotspot host scans clients on the stable lan share port") { `hotspot host scans clients on the stable lan share port`() }

        test("ordinary wifi host scans same subnet and excludes self") { `ordinary wifi host scans same subnet and excludes self`() }

        test("active discovery does not scan non ipv4 bind hosts") { `active discovery does not scan non ipv4 bind hosts`() }
    }


    private fun `hotspot host scans clients on the stable lan share port`() {
        val targets = buildLanShareActiveDiscoveryTargets(bindHost = "192.168.43.1")

        (targets.contains(LanShareActiveDiscoveryTarget("192.168.43.2", LAN_SHARE_DISCOVERY_PORT))).shouldBeTrue()
        (targets.contains(LanShareActiveDiscoveryTarget("192.168.43.254", LAN_SHARE_DISCOVERY_PORT))).shouldBeTrue()
        (targets.any { it.host == "192.168.43.1" }).shouldBeFalse()
    }

    private fun `ordinary wifi host scans same subnet and excludes self`() {
        val targets = buildLanShareActiveDiscoveryTargets(bindHost = "192.168.1.37")

        targets.first().port shouldBe LAN_SHARE_DISCOVERY_PORT
        (targets.contains(LanShareActiveDiscoveryTarget("192.168.1.1", LAN_SHARE_DISCOVERY_PORT))).shouldBeTrue()
        (targets.contains(LanShareActiveDiscoveryTarget("192.168.1.254", LAN_SHARE_DISCOVERY_PORT))).shouldBeTrue()
        (targets.any { it.host == "192.168.1.37" }).shouldBeFalse()
    }

    private fun `active discovery does not scan non ipv4 bind hosts`() {
        buildLanShareActiveDiscoveryTargets("fd00::24") shouldBe emptyList<LanShareActiveDiscoveryTarget>()
        buildLanShareActiveDiscoveryTargets("localhost") shouldBe emptyList<LanShareActiveDiscoveryTarget>()
    }
}
