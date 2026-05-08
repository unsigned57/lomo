package com.lomo.data.share

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: LAN share address policy helper.
 * - Behavior focus: private-host allowlist, bind-address selection, and active LAN network
 *   selection for LAN-only cleartext traffic on Wi-Fi, ethernet, and local hotspot networks.
 * - Observable outcomes: boolean host allow/deny results, selected bind-host string, and selected
 *   LAN network snapshot from representative network probes.
 * - Red phase: Fails before the fix because no shared LAN address policy exists to reject public peers
 *   or to prefer a private bind address for the embedded share server; hotspot selection fails
 *   before the current fix because the policy only considers ConnectivityManager.activeNetwork.
 * - Excludes: real ConnectivityManager callbacks, NSD transport, and live socket binding.
 */
class LanShareAddressPolicyTest {
    @Test
    fun `isLanSharePrivateHost accepts private and loopback hosts`() {
        assertTrue(isLanSharePrivateHost("127.0.0.1"))
        assertTrue(isLanSharePrivateHost("10.0.0.8"))
        assertTrue(isLanSharePrivateHost("172.20.1.9"))
        assertTrue(isLanSharePrivateHost("192.168.1.25"))
        assertTrue(isLanSharePrivateHost("[fd00::24]"))
    }

    @Test
    fun `isLanSharePrivateHost rejects public hosts`() {
        assertFalse(isLanSharePrivateHost("8.8.8.8"))
        assertFalse(isLanSharePrivateHost("54.85.12.3"))
        assertFalse(isLanSharePrivateHost("[2001:4860:4860::8888]"))
    }

    @Test
    fun `selectLanShareBindHostAddress prefers private ipv4`() {
        val bindHost =
            selectLanShareBindHostAddress(
                listOf(
                    InetAddress.getByName("fd00::25"),
                    InetAddress.getByName("192.168.1.26"),
                ),
            )

        assertEquals("192.168.1.26", bindHost)
    }

    @Test
    fun `selectLanShareBindHostAddress falls back to unique local ipv6`() {
        val bindHost = selectLanShareBindHostAddress(listOf(InetAddress.getByName("fd00::24")))

        assertEquals("fd00:0:0:0:0:0:0:24", bindHost)
    }

    @Test
    fun `active snapshot falls back to local hotspot network when active network is public cellular`() {
        val snapshot =
            selectLanShareActiveNetworkSnapshot(
                listOf(
                    LanShareNetworkProbe(
                        networkKey = "cellular",
                        bindHost = null,
                        isActiveNetwork = true,
                        hasWifiTransport = false,
                        hasEthernetTransport = false,
                        hasLocalNetworkCapability = false,
                    ),
                    LanShareNetworkProbe(
                        networkKey = "hotspot-local",
                        bindHost = "192.168.43.1",
                        isActiveNetwork = false,
                        hasWifiTransport = false,
                        hasEthernetTransport = false,
                        hasLocalNetworkCapability = true,
                    ),
                ),
            )

        assertEquals(
            LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1"),
            snapshot,
        )
    }

    @Test
    fun `active snapshot prefers ordinary wifi before local hotspot network`() {
        val snapshot =
            selectLanShareActiveNetworkSnapshot(
                listOf(
                    LanShareNetworkProbe(
                        networkKey = "hotspot-local",
                        bindHost = "192.168.43.1",
                        isActiveNetwork = true,
                        hasWifiTransport = false,
                        hasEthernetTransport = false,
                        hasLocalNetworkCapability = true,
                    ),
                    LanShareNetworkProbe(
                        networkKey = "wifi",
                        bindHost = "192.168.1.22",
                        isActiveNetwork = false,
                        hasWifiTransport = true,
                        hasEthernetTransport = false,
                        hasLocalNetworkCapability = false,
                    ),
                ),
            )

        assertEquals(
            LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.22"),
            snapshot,
        )
    }

    @Test
    fun `active snapshot rejects networks without private lan bind host`() {
        val snapshot =
            selectLanShareActiveNetworkSnapshot(
                listOf(
                    LanShareNetworkProbe(
                        networkKey = "cellular",
                        bindHost = null,
                        isActiveNetwork = true,
                        hasWifiTransport = false,
                        hasEthernetTransport = false,
                        hasLocalNetworkCapability = false,
                    ),
                ),
            )

        assertNull(snapshot)
    }

    @Test
    fun `interface fallback selects hotspot host interface when connectivity network is unavailable`() {
        val snapshot =
            selectLanShareInterfaceFallbackSnapshot(
                listOf(
                    LanShareInterfaceProbe(
                        name = "rmnet_data0",
                        addresses = listOf(InetAddress.getByName("10.6.2.18")),
                        isUp = true,
                        isLoopback = false,
                        isVirtual = false,
                        isPointToPoint = false,
                    ),
                    LanShareInterfaceProbe(
                        name = "ap0",
                        addresses = listOf(InetAddress.getByName("192.168.43.1")),
                        isUp = true,
                        isLoopback = false,
                        isVirtual = false,
                        isPointToPoint = false,
                    ),
                ),
            )

        assertEquals(
            LanShareActiveNetworkSnapshot(networkKey = "if:ap0", bindHost = "192.168.43.1"),
            snapshot,
        )
    }

    @Test
    fun `interface fallback rejects cellular private nat interfaces`() {
        val snapshot =
            selectLanShareInterfaceFallbackSnapshot(
                listOf(
                    LanShareInterfaceProbe(
                        name = "rmnet_data0",
                        addresses = listOf(InetAddress.getByName("10.6.2.18")),
                        isUp = true,
                        isLoopback = false,
                        isVirtual = false,
                        isPointToPoint = false,
                    ),
                    LanShareInterfaceProbe(
                        name = "tun0",
                        addresses = listOf(InetAddress.getByName("10.8.0.2")),
                        isUp = true,
                        isLoopback = false,
                        isVirtual = false,
                        isPointToPoint = true,
                    ),
                ),
            )

        assertNull(snapshot)
    }
}
