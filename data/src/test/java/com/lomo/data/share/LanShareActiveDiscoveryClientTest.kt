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



import com.lomo.domain.model.DiscoveredDevice
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Behavior Contract:
 * - Unit under test: LAN share active-discovery client.
 *
 * Scenarios:
 * - Happy: standard happy path for LanShareActiveDiscoveryClientTest.
 * - Boundary: boundary and edge cases for LanShareActiveDiscoveryClientTest.
 * - Failure: failure and error scenarios for LanShareActiveDiscoveryClientTest.
 * - Must-not-happen: invariants are never violated for LanShareActiveDiscoveryClientTest.
 * - Behavior focus: active discovery must map the stable /share/ping response into discovered
 *   devices and deduplicate probe results from same-Wi-Fi or hotspot subnet scans.
 * - Observable outcomes: parsed DiscoveredDevice values and scan result list.
 * - TDD proof: Fails before the fix because ping-response mapping is private to the HTTP probe,
 *   active discovery has no focused contract for merging probe results, and hotspot scans do not
 *   bind probes to the local-only Android Network.
 * - Excludes: live HTTP sockets, Android NSD callbacks, timeout tuning, and ConnectivityManager.
 */
class LanShareActiveDiscoveryClientTest : DataFunSpec() {
    init {
        test("ping response maps to discovered device on stable share port") { `ping response maps to discovered device on stable share port`() }

        test("invalid ping response is ignored") { `invalid ping response is ignored`() }

        test("scan deduplicates devices returned by active probes") { `scan deduplicates devices returned by active probes`() }

        test("scan binds hotspot probes to local network when available") { `scan binds hotspot probes to local network when available`() }
    }




    private fun `ping response maps to discovered device on stable share port`() {
        val target = LanShareActiveDiscoveryTarget(host = "192.168.1.42", port = LAN_SHARE_DISCOVERY_PORT)

        val device = mapLanSharePingResponse(target, "lomo-share\tPixel 8\n")

        device shouldBe DiscoveredDevice(name = "Pixel 8", host = "192.168.1.42", port = LAN_SHARE_DISCOVERY_PORT)
    }

    private fun `invalid ping response is ignored`() {
        val target = LanShareActiveDiscoveryTarget(host = "192.168.1.42", port = LAN_SHARE_DISCOVERY_PORT)

        mapLanSharePingResponse(target, "not-lomo").shouldBeNull()
        mapLanSharePingResponse(target, "lomo-share\t   ").shouldBeNull()
    }

    private fun `scan deduplicates devices returned by active probes`() =
        runTest {
            val duplicate =
                DiscoveredDevice(
                    name = "Pixel",
                    host = "192.168.1.2",
                    port = LAN_SHARE_DISCOVERY_PORT,
                )
            val client =
                LanShareActiveDiscoveryClient(
                    probeDevice = { target ->
                        when (target.host) {
                            "192.168.1.2",
                            "192.168.1.3",
                            -> duplicate

                            else -> null
                        }
                    },
                )

            val devices =
                client.scan(
                    LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.37"),
                )

            devices shouldBe listOf(duplicate)
        }

    private fun `scan binds hotspot probes to local network when available`() =
        runTest {
            val hotspotNetwork = mockk<android.net.Network>()
            val probeNetworks = mutableListOf<android.net.Network?>()
            val client =
                LanShareActiveDiscoveryClient(
                    probeDevice = { target ->
                        if (target.host == "192.168.43.2") {
                            probeNetworks += target.network
                            DiscoveredDevice(
                                name = "Hotspot peer",
                                host = target.host,
                                port = target.port,
                            )
                        } else {
                            null
                        }
                    },
                )

            val devices =
                client.scan(
                    LanShareActiveNetworkSnapshot(
                        networkKey = "hotspot-local",
                        bindHost = "192.168.43.1",
                        network = hotspotNetwork,
                    ),
                )

            devices shouldBe listOf(DiscoveredDevice("Hotspot peer", "192.168.43.2", LAN_SHARE_DISCOVERY_PORT))
            (probeNetworks.single() === hotspotNetwork).shouldBeTrue()
        }
}
