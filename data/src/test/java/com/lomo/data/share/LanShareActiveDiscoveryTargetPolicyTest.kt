package com.lomo.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
class LanShareActiveDiscoveryTargetPolicyTest {
    @Test
    fun `hotspot host scans clients on the stable lan share port`() {
        val targets = buildLanShareActiveDiscoveryTargets(bindHost = "192.168.43.1")

        assertTrue(targets.contains(LanShareActiveDiscoveryTarget("192.168.43.2", LAN_SHARE_DISCOVERY_PORT)))
        assertTrue(targets.contains(LanShareActiveDiscoveryTarget("192.168.43.254", LAN_SHARE_DISCOVERY_PORT)))
        assertFalse(targets.any { it.host == "192.168.43.1" })
    }

    @Test
    fun `ordinary wifi host scans same subnet and excludes self`() {
        val targets = buildLanShareActiveDiscoveryTargets(bindHost = "192.168.1.37")

        assertEquals(LAN_SHARE_DISCOVERY_PORT, targets.first().port)
        assertTrue(targets.contains(LanShareActiveDiscoveryTarget("192.168.1.1", LAN_SHARE_DISCOVERY_PORT)))
        assertTrue(targets.contains(LanShareActiveDiscoveryTarget("192.168.1.254", LAN_SHARE_DISCOVERY_PORT)))
        assertFalse(targets.any { it.host == "192.168.1.37" })
    }

    @Test
    fun `active discovery does not scan non ipv4 bind hosts`() {
        assertEquals(emptyList<LanShareActiveDiscoveryTarget>(), buildLanShareActiveDiscoveryTargets("fd00::24"))
        assertEquals(emptyList<LanShareActiveDiscoveryTarget>(), buildLanShareActiveDiscoveryTargets("localhost"))
    }
}
