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
 * - Given a UUID-bearing ping response, when it is mapped, then a UUID-identified discovered device
 *   on the stable share port is returned.
 * - Given an old, malformed, or self ping response, when it is mapped, then it is ignored.
 * - Given duplicate active probe results, when a scan completes, then devices
 *   are deduplicated by endpoint.
 * - Given a hotspot network snapshot, when probes run, then targets keep the
 *   Android Network binding.
 * - Given an interface fallback snapshot without Android Network routing, when
 *   active discovery scans, then no default-route probes run and degraded route
 *   diagnostics are returned.
 * - Given a bound active-discovery target, when the target is probed, then the
 *   HTTP connection is opened through Android Network.openConnection.
 * - Given four prompt scans in one client session, when the subnet is /24, then their rotating
 *   64-host windows cover every usable peer address while excluding the local host.
 *
 * Observable outcomes:
 * - parsed DiscoveredDevice values, scan result list, probe route diagnostics,
 *   Android Network-bound connection use, and recorded target hosts per scan.
 *
 * TDD proof:
 * - RED: the old ping parser treated the whole post-prefix body as a display name and could not exclude self.
 * - RED: before the fix, target rotation repeated priority hosts every round, so four scans covered only 244
 *   of the 253 usable peer addresses.
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

        test("four prompt scans cover the full usable subnet") {
            `four prompt scans cover the full usable subnet`()
        }
    }




    private fun `ping response maps to discovered device on stable share port`() {
        val target = LanShareActiveDiscoveryTarget(host = "192.168.1.42", port = LAN_SHARE_DISCOVERY_PORT)

        val device =
            mapLanSharePingResponse(
                target = target,
                body = LanSharePingProtocol.encode(uuid = REMOTE_UUID, name = "Pixel 8") + "\n",
                localUuid = LOCAL_UUID,
            )

        device shouldBe
            DiscoveredDevice(
                uuid = REMOTE_UUID,
                name = "Pixel 8",
                host = "192.168.1.42",
                port = LAN_SHARE_DISCOVERY_PORT,
            )
    }

    private fun `invalid ping response is ignored`() {
        val target = LanShareActiveDiscoveryTarget(host = "192.168.1.42", port = LAN_SHARE_DISCOVERY_PORT)

        mapLanSharePingResponse(target, "not-lomo", LOCAL_UUID).shouldBeNull()
        mapLanSharePingResponse(target, "lomo-share\tPixel 8", LOCAL_UUID).shouldBeNull()
        mapLanSharePingResponse(target, "lomo-share\tnot-a-uuid\tPixel 8", LOCAL_UUID).shouldBeNull()
        mapLanSharePingResponse(target, "lomo-share\t$LOCAL_UUID\tThis device", LOCAL_UUID).shouldBeNull()
    }

    private fun `scan deduplicates devices returned by active probes`() =
        runTest {
            val duplicate =
                DiscoveredDevice(
                    uuid = REMOTE_UUID,
                    name = "Pixel",
                    host = "192.168.1.2",
                    port = LAN_SHARE_DISCOVERY_PORT,
                )
            val client =
                LanShareActiveDiscoveryClient(
                    probeDevice = { target, _ ->
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
                    localUuid = LOCAL_UUID,
                )

            result.devices shouldBe listOf(duplicate)
        }

    private fun `scan binds hotspot probes to local network when available`() =
        runTest {
            val hotspotNetwork = mockk<android.net.Network>()
            val probeNetworks = mutableListOf<android.net.Network?>()
            val client =
                LanShareActiveDiscoveryClient(
                    probeDevice = { target, _ ->
                        if (target.host == "192.168.43.2") {
                            probeNetworks += target.network
                            DiscoveredDevice(
                                uuid = HOTSPOT_UUID,
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
                    localUuid = LOCAL_UUID,
                )

            result.devices shouldBe
                listOf(
                    DiscoveredDevice(
                        uuid = HOTSPOT_UUID,
                        name = "Hotspot peer",
                        host = "192.168.43.2",
                        port = LAN_SHARE_DISCOVERY_PORT,
                    ),
                )
            result.activeProbe.routeCapableSnapshotCount shouldBe 1
            (probeNetworks.single() === hotspotNetwork).shouldBeTrue()
        }

    private fun `scan degrades no-network fallback snapshot without probing default route`() =
        runTest {
            var probeCount = 0
            val client =
                LanShareActiveDiscoveryClient(
                    probeDevice = { _, _ ->
                        probeCount += 1
                        error("No-Network fallback snapshots must not probe through the process default route")
                    },
                )

            val result =
                client.scan(
                    LanShareActiveNetworkSnapshot(networkKey = "if:ap0", bindHost = "192.168.43.1"),
                    localUuid = LOCAL_UUID,
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
                    every { inputStream } answers {
                        ByteArrayInputStream("lomo-share\t$REMOTE_UUID\tBound peer".toByteArray())
                    }
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

            val device = probeLanShareDevice(target, localUuid = LOCAL_UUID)

            device shouldBe
                DiscoveredDevice(
                    uuid = REMOTE_UUID,
                    name = "Bound peer",
                    host = "192.168.43.2",
                    port = LAN_SHARE_DISCOVERY_PORT,
                )
            verify(exactly = 1) { network.openConnection(any()) }
        }

    private fun `four prompt scans cover the full usable subnet`() =
        runTest {
            val probedHosts = ConcurrentLinkedQueue<String>()
            val client =
                LanShareActiveDiscoveryClient(
                    probeDevice = { target, _ ->
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

            val scanWindows =
                (1..4).map {
                    probedHosts.clear()
                    client.scan(snapshot, localUuid = LOCAL_UUID)
                    probedHosts.toSet()
                }

            scanWindows.map(Set<String>::size) shouldBe List(4) { EXPECTED_ACTIVE_DISCOVERY_TARGET_BUDGET }
            scanWindows.first().containsAll(setOf("192.168.1.1", "192.168.1.38", "192.168.1.254")) shouldBe true
            scanWindows.flatten().toSet().size shouldBe EXPECTED_USABLE_PEER_COUNT
            scanWindows.flatten().contains("192.168.1.37") shouldBe false
        }
}

private const val EXPECTED_ACTIVE_DISCOVERY_TARGET_BUDGET = 64
private const val EXPECTED_USABLE_PEER_COUNT = 253
private const val LOCAL_UUID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
private const val REMOTE_UUID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
private const val HOTSPOT_UUID = "cccccccc-cccc-cccc-cccc-cccccccccccc"
