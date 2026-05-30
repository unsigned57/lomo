/*
 * Behavior Contract:
 * - Unit under test: LAN share active-discovery client.
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: probe active LAN share targets within a bounded per-scan
 *   budget while preserving stable response mapping, de-duplication, network
 *   binding, and rotating coverage across a discovery session.
 *
 * Scenarios:
 * - Given a stable ping response, when it is mapped, then a discovered device
 *   on the stable share port is returned.
 * - Given invalid ping responses, when they are mapped, then they are ignored.
 * - Given duplicate active probe results, when a scan completes, then devices
 *   are deduplicated by endpoint.
 * - Given a hotspot network snapshot, when probes run, then targets keep the
 *   Android Network binding.
 * - Given repeated scans in one client session, when a subnet is larger than
 *   the per-scan budget, then priority hosts are retained and the non-priority
 *   budget window advances.
 *
 * Observable outcomes:
 * - parsed DiscoveredDevice values, scan result list, probe network identity,
 *   and recorded target hosts per scan.
 *
 * TDD proof:
 * - RED before the rotation fix because repeated scans reuse the same first
 *   budgeted target list and never reach the next non-priority window.
 *
 * Excludes:
 * - live HTTP sockets, Android NSD callbacks, timeout tuning, and
 *   ConnectivityManager.
 */
package com.lomo.data.share

import com.lomo.domain.model.DiscoveredDevice
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentLinkedQueue

class LanShareActiveDiscoveryClientTest : DataFunSpec() {
    init {
        test("ping response maps to discovered device on stable share port") { `ping response maps to discovered device on stable share port`() }

        test("invalid ping response is ignored") { `invalid ping response is ignored`() }

        test("scan deduplicates devices returned by active probes") { `scan deduplicates devices returned by active probes`() }

        test("scan binds hotspot probes to local network when available") { `scan binds hotspot probes to local network when available`() }

        test("repeated scans keep priority hosts and rotate non priority budget window") {
            `repeated scans keep priority hosts and rotate non priority budget window`()
        }
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

    private fun `repeated scans keep priority hosts and rotate non priority budget window`() =
        runTest {
            val probedHosts = ConcurrentLinkedQueue<String>()
            val client =
                LanShareActiveDiscoveryClient(
                    probeDevice = { target ->
                        probedHosts += target.host
                        null
                    },
                )
            val snapshot = LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.37")

            client.scan(snapshot)
            val firstScanHosts = probedHosts.toSet()
            probedHosts.clear()
            client.scan(snapshot)
            val secondScanHosts = probedHosts.toSet()

            firstScanHosts.size shouldBe EXPECTED_ACTIVE_DISCOVERY_TARGET_BUDGET
            secondScanHosts.size shouldBe EXPECTED_ACTIVE_DISCOVERY_TARGET_BUDGET
            firstScanHosts.containsAll(setOf("192.168.1.1", "192.168.1.38", "192.168.1.254")) shouldBe true
            secondScanHosts.containsAll(setOf("192.168.1.1", "192.168.1.38", "192.168.1.254")) shouldBe true
            firstScanHosts.contains("192.168.1.65") shouldBe false
            secondScanHosts.contains("192.168.1.65") shouldBe true
        }
}

private const val EXPECTED_ACTIVE_DISCOVERY_TARGET_BUDGET = 64
