package com.lomo.data.share

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



import java.net.InetAddress
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Behavior Contract:
 * - Unit under test: LAN share address policy helper.
 * - Behavior focus: private-host allowlist, bind-address selection, and active LAN network
 *   selection for LAN-only cleartext traffic on Wi-Fi, ethernet, and local hotspot networks.
 * - Observable outcomes: boolean host allow/deny results, selected bind-host string, and selected
 *   LAN network snapshot from representative network probes.
 * - TDD proof: Fails before the fix because no shared LAN address policy exists to reject public peers
 *   or to prefer a private bind address for the embedded share server; hotspot selection fails
 *   before the current fix because the policy only considers ConnectivityManager.activeNetwork.
 * - Excludes: real ConnectivityManager callbacks, NSD transport, and live socket binding.
 */
/*
 * Test Change Justification:
 * - Reason category: hotspot + Wi-Fi coexistence contract extension (multi-network discovery refactor).
 * - Old behavior/assertion being replaced: policy exposed only single-snapshot selectors; the
 *   ConnectivityManager path silently discarded interface-only fallbacks while a Wi-Fi network was
 *   present, so hotspot subnet was unreachable when the host also held a Wi-Fi link.
 * - Why old assertion is no longer correct: production now enumerates every eligible LAN snapshot so
 *   that NSD and active scans can fan out across both subnets concurrently.
 * - Coverage preserved by: the existing single-snapshot selectors retain their tests as the
 *   "preferred snapshot" thin wrappers; new tests assert the multi-result contract on the new
 *   "eligible" selectors and the merge helper.
 * - Why this is not fitting the test to the implementation: the new contract encodes the
 *   externally observable hotspot+Wi-Fi recovery scenario reported by users.
 */
class LanShareAddressPolicyTest : DataFunSpec() {
    init {
        test("isLanSharePrivateHost accepts private and loopback hosts") { `isLanSharePrivateHost accepts private and loopback hosts`() }

        test("isLanSharePrivateHost rejects public hosts") { `isLanSharePrivateHost rejects public hosts`() }

        test("selectLanShareBindHostAddress prefers private ipv4") { `selectLanShareBindHostAddress prefers private ipv4`() }

        test("selectLanShareBindHostAddress falls back to unique local ipv6") { `selectLanShareBindHostAddress falls back to unique local ipv6`() }

        test("active snapshot falls back to local hotspot network when active network is public cellular") { `active snapshot falls back to local hotspot network when active network is public cellular`() }

        test("active snapshot prefers ordinary wifi before local hotspot network") { `active snapshot prefers ordinary wifi before local hotspot network`() }

        test("active snapshot rejects networks without private lan bind host") { `active snapshot rejects networks without private lan bind host`() }

        test("interface fallback selects hotspot host interface when connectivity network is unavailable") { `interface fallback selects hotspot host interface when connectivity network is unavailable`() }

        test("interface fallback rejects cellular private nat interfaces") { `interface fallback rejects cellular private nat interfaces`() }

        test("eligible network snapshots include every qualifying probe in priority order") { `eligible network snapshots include every qualifying probe in priority order`() }

        test("eligible network snapshots skip probes without private bind host") { `eligible network snapshots skip probes without private bind host`() }

        test("eligible interface fallback snapshots include every qualifying interface in priority order") { `eligible interface fallback snapshots include every qualifying interface in priority order`() }

        test("merge eligible snapshots drops interface fallbacks duplicating a network probe bind host") { `merge eligible snapshots drops interface fallbacks duplicating a network probe bind host`() }

        test("merge eligible snapshots keeps both wifi probe and hotspot interface fallback when bind hosts differ") { `merge eligible snapshots keeps both wifi probe and hotspot interface fallback when bind hosts differ`() }
    }


    private fun `isLanSharePrivateHost accepts private and loopback hosts`() {
        (isLanSharePrivateHost("127.0.0.1")).shouldBeTrue()
        (isLanSharePrivateHost("10.0.0.8")).shouldBeTrue()
        (isLanSharePrivateHost("172.20.1.9")).shouldBeTrue()
        (isLanSharePrivateHost("192.168.1.25")).shouldBeTrue()
        (isLanSharePrivateHost("[fd00::24]")).shouldBeTrue()
    }

    private fun `isLanSharePrivateHost rejects public hosts`() {
        (isLanSharePrivateHost("8.8.8.8")).shouldBeFalse()
        (isLanSharePrivateHost("54.85.12.3")).shouldBeFalse()
        (isLanSharePrivateHost("[2001:4860:4860::8888]")).shouldBeFalse()
    }

    private fun `selectLanShareBindHostAddress prefers private ipv4`() {
        val bindHost =
            selectLanShareBindHostAddress(
                listOf(
                    InetAddress.getByName("fd00::25"),
                    InetAddress.getByName("192.168.1.26"),
                ),
            )

        bindHost shouldBe "192.168.1.26"
    }

    private fun `selectLanShareBindHostAddress falls back to unique local ipv6`() {
        val bindHost = selectLanShareBindHostAddress(listOf(InetAddress.getByName("fd00::24")))

        bindHost shouldBe "fd00:0:0:0:0:0:0:24"
    }

    private fun `active snapshot falls back to local hotspot network when active network is public cellular`() {
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

        snapshot shouldBe LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1")
    }

    private fun `active snapshot prefers ordinary wifi before local hotspot network`() {
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

        snapshot shouldBe LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.22")
    }

    private fun `active snapshot rejects networks without private lan bind host`() {
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

        snapshot.shouldBeNull()
    }

    private fun `interface fallback selects hotspot host interface when connectivity network is unavailable`() {
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

        snapshot shouldBe LanShareActiveNetworkSnapshot(networkKey = "if:ap0", bindHost = "192.168.43.1")
    }

    private fun `interface fallback rejects cellular private nat interfaces`() {
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

        snapshot.shouldBeNull()
    }

    private fun `eligible network snapshots include every qualifying probe in priority order`() {
        val snapshots =
            selectLanShareEligibleNetworkSnapshots(
                listOf(
                    LanShareNetworkProbe(
                        networkKey = "hotspot-local",
                        bindHost = "192.168.43.1",
                        isActiveNetwork = false,
                        hasWifiTransport = false,
                        hasEthernetTransport = false,
                        hasLocalNetworkCapability = true,
                    ),
                    LanShareNetworkProbe(
                        networkKey = "cellular",
                        bindHost = null,
                        isActiveNetwork = true,
                        hasWifiTransport = false,
                        hasEthernetTransport = false,
                        hasLocalNetworkCapability = false,
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

        snapshots shouldBe
            listOf(
                LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.22"),
                LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1"),
            )
    }

    private fun `eligible network snapshots skip probes without private bind host`() {
        val snapshots =
            selectLanShareEligibleNetworkSnapshots(
                listOf(
                    LanShareNetworkProbe(
                        networkKey = "wifi-no-host",
                        bindHost = null,
                        isActiveNetwork = true,
                        hasWifiTransport = true,
                        hasEthernetTransport = false,
                        hasLocalNetworkCapability = false,
                    ),
                ),
            )

        snapshots shouldBe emptyList<LanShareActiveNetworkSnapshot>()
    }

    private fun `eligible interface fallback snapshots include every qualifying interface in priority order`() {
        val snapshots =
            selectLanShareEligibleInterfaceFallbackSnapshots(
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
                        name = "wlan0",
                        addresses = listOf(InetAddress.getByName("192.168.1.7")),
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

        snapshots shouldBe
            listOf(
                LanShareActiveNetworkSnapshot(networkKey = "if:ap0", bindHost = "192.168.43.1"),
                LanShareActiveNetworkSnapshot(networkKey = "if:wlan0", bindHost = "192.168.1.7"),
            )
    }

    private fun `merge eligible snapshots drops interface fallbacks duplicating a network probe bind host`() {
        val networkSnapshots =
            listOf(
                LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.7"),
            )
        val interfaceSnapshots =
            listOf(
                LanShareActiveNetworkSnapshot(networkKey = "if:wlan0", bindHost = "192.168.1.7"),
            )

        val merged = mergeLanShareEligibleSnapshots(networkSnapshots, interfaceSnapshots)

        merged shouldBe
            listOf(
                LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.7"),
            )
    }

    private fun `merge eligible snapshots keeps both wifi probe and hotspot interface fallback when bind hosts differ`() {
        val hotspotNetwork = io.mockk.mockk<android.net.Network>()
        val networkSnapshots =
            listOf(
                LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.7", network = hotspotNetwork),
            )
        val interfaceSnapshots =
            listOf(
                LanShareActiveNetworkSnapshot(networkKey = "if:ap0", bindHost = "192.168.43.1"),
            )

        val merged = mergeLanShareEligibleSnapshots(networkSnapshots, interfaceSnapshots)

        merged shouldBe
            listOf(
                LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.7", network = hotspotNetwork),
                LanShareActiveNetworkSnapshot(networkKey = "if:ap0", bindHost = "192.168.43.1"),
            )
    }
}
