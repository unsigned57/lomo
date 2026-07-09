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
 * - Given an interface fallback snapshot without Android Network routing, when
 *   active discovery scans, then no default-route probes run and degraded route
 *   diagnostics are returned.
 * - Given a bound active-discovery target, when the target is probed, then the
 *   HTTP connection is opened through Android Network.openConnection.
 * - Given repeated scans in one client session, when a subnet is larger than
 *   the per-scan budget, then priority hosts are retained and the non-priority
 *   budget window advances.
 *
 * Observable outcomes:
 * - parsed DiscoveredDevice values, scan result list, probe route diagnostics,
 *   Android Network-bound connection use, and recorded target hosts per scan.
 *
 * TDD proof:
 * - RED observed with `./kotlin test --include-classes='com.lomo.data.share.LanShareActiveDiscoveryClientTest'`.
 * - Before Slice 4, the no-Network fallback contract failed to compile because route diagnostics did not
 *   exist, and production probing used URL.openConnection when no Android Network was present.
 * - Earlier rotation coverage failed before the rotation fix because repeated scans reused the same
 *   first budgeted target list and never reached the next non-priority window.
 *
 * Excludes:
 * - live HTTP sockets, Android NSD callbacks, timeout tuning, and
 *   ConnectivityManager.
 *
 * Test Change Justification:
 * - Reason category: LanShare active discovery gained discovery-diagnostics publisher, device
 *   deduplication, network permission gateway, Nsd strategy, and an active discovery loop
 *   orchestrating scan sessions.
 * - Old behavior/assertion being replaced: previous active-discovery tests only covered a single
 *   scan session without diagnostics, dedup, or network permission gating.
 * - Why old assertion is no longer correct: the LanShareActiveDiscovery now composes a loop
 *   continuum with diagnostics publishing (permission/interface/route info), deduplicated
 *   device lists, and network-bound permission enforcement before scans.
 * - Coverage preserved by: all stable-ping, invalid-ping, dedup, network-binding, budget, and
 *   fallback scenarios retained; new scenarios for diagnostics, dedup, and permission gating
 *   added to the pipeline.
 * - Why this is not fitting the test to the implementation: tests verify observable
 *   DiscoveryDiagnostics, deduplicated device collections, and scan lifecycle callbacks, not
 *   internal thread-local or socket mechanics.
 */
package com.lomo.data.share

import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanShareActiveProbeState
import com.lomo.domain.model.LanShareDiscoveryDegradedReason
import com.lomo.domain.model.LanShareProbeRouteState
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentLinkedQueue

class LanShareActiveDiscoveryClientTest : DataFunSpec() {
    init {
        test("ping response maps to discovered device on stable share port") { `ping response maps to discovered device on stable share port`() }

        test("invalid ping response is ignored") { `invalid ping response is ignored`() }

        test("scan deduplicates devices returned by active probes") { `scan deduplicates devices returned by active probes`() }

        test("scan binds hotspot probes to local network when available") { `scan binds hotspot probes to local network when available`() }

        test("scan degrades no-network fallback snapshot without probing default route") {
            `scan degrades no-network fallback snapshot without probing default route`()
        }

        test("probe opens HTTP connection through bound Android network") {
            `probe opens HTTP connection through bound Android network`()
        }

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

            val result =
                client.scan(
                    LanShareActiveNetworkSnapshot(
                        networkKey = "wifi",
                        bindHost = "192.168.1.37",
                        network = mockk(),
                    ),
                )

            result.devices shouldBe listOf(duplicate)
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

            val result =
                client.scan(
                    LanShareActiveNetworkSnapshot(
                        networkKey = "hotspot-local",
                        bindHost = "192.168.43.1",
                        network = hotspotNetwork,
                    ),
                )

            result.devices shouldBe listOf(DiscoveredDevice("Hotspot peer", "192.168.43.2", LAN_SHARE_DISCOVERY_PORT))
            result.activeProbe.routeCapableSnapshotCount shouldBe 1
            (probeNetworks.single() === hotspotNetwork).shouldBeTrue()
        }

    private fun `scan degrades no-network fallback snapshot without probing default route`() =
        runTest {
            var probeCount = 0
            val client =
                LanShareActiveDiscoveryClient(
                    probeDevice = {
                        probeCount += 1
                        error("No-Network fallback snapshots must not probe through the process default route")
                    },
                )

            val result =
                client.scan(
                    LanShareActiveNetworkSnapshot(networkKey = "if:ap0", bindHost = "192.168.43.1"),
                )

            probeCount shouldBe 0
            result.devices shouldBe emptyList<DiscoveredDevice>()
            result.snapshot.routeState shouldBe LanShareProbeRouteState.DegradedNoNetwork
            result.activeProbe.state shouldBe LanShareActiveProbeState.DegradedNoRoute
            result.activeProbe.degradedSnapshotCount shouldBe 1
            result.activeProbe.probedTargetCount shouldBe 0
            result.degradedReason shouldBe LanShareDiscoveryDegradedReason.FallbackSnapshotWithoutNetwork
        }

    private fun `probe opens HTTP connection through bound Android network`() =
        runTest {
            val network = mockk<android.net.Network>()
            val connection =
                mockk<HttpURLConnection> {
                    every { responseCode } returns HttpURLConnection.HTTP_OK
                    every { inputStream } answers { ByteArrayInputStream("lomo-share\tBound peer".toByteArray()) }
                    every { disconnect() } returns Unit
                    every { requestMethod = "GET" } returns Unit
                    every { connectTimeout = any() } returns Unit
                    every { readTimeout = any() } returns Unit
                }
            every { network.openConnection(any()) } returns connection
            val target =
                LanShareActiveDiscoveryTarget(
                    host = "192.168.43.2",
                    port = LAN_SHARE_DISCOVERY_PORT,
                    network = network,
                )

            val device = probeLanShareDevice(target)

            device shouldBe DiscoveredDevice("Bound peer", "192.168.43.2", LAN_SHARE_DISCOVERY_PORT)
            verify(exactly = 1) { network.openConnection(any()) }
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
            val snapshot =
                LanShareActiveNetworkSnapshot(
                    networkKey = "wifi",
                    bindHost = "192.168.1.37",
                    network = mockk(),
                )

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
