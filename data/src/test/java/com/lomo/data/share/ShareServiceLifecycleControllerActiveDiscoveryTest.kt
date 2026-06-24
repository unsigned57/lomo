package com.lomo.data.share

/*
 * Behavior Contract:
 * - Unit under test: com.lomo.data.share.ShareServiceLifecycleController active LAN discovery orchestration.
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: keep LAN fallback discovery responsive without fixed one-second infinite subnet scans.
 *
 * Scenarios:
 * - Given discovery starts with an eligible LAN snapshot, when the active scanner is scheduled, then the first scan runs immediately.
 * - Given LAN share services are desired before topology exists, when a LAN network later appears,
 *   then the service starts without requiring another UI command.
 * - Given an active scan finds no peers, when time advances by the legacy one-second interval,
 *   then no retry runs until the empty-scan backoff elapses.
 * - Given an active scan finds a peer, when discovery remains active, then the next scan is slowed to the discovered-peer cadence.
 * - Given discovery is already active and backing off, when startDiscovery is called again, then the current cadence is preserved.
 * - Given discovery is stopped and started again, when a previous session was backing off, then the new session starts with a prompt scan.
 * - Given NSD registration or discovery is unavailable, when active probing finds peers,
 *   then those peers are merged without requiring live NSD packets.
 * - Given LAN sharing is disabled or no snapshot exists, when services/discovery are requested, then no phantom devices are emitted.
 *
 * Observable outcomes:
 * - server start parameters, NSD registration/discovery requests, active scan timing/order,
 *   merged devices, cancellation, and startup failure events.
 *
 * TDD proof:
 * - RED observed with `./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.share.ShareServiceLifecycleControllerActiveDiscoveryTest' --tests 'com.lomo.data.share.LanShareActiveDiscoveryPolicyTest'`.
 * - The redundant-start scenario failed before the follow-up fix because calling startDiscovery while active restarted the loop, reset the schedule policy to 0 ms, and ran a second prompt scan.
 * - Earlier scheduling scenarios failed before Batch 2A because active discovery retried on a fixed 1,000 ms loop instead of applying empty backoff, discovered-peer cadence, and session reset policy.
 *
 * Excludes:
 * - live sockets, real NSD packets, Android runtime permission UI, transfer payload encoding, and /24 target construction.
 *
 * Test Change Justification:
 * - Reason category: active discovery scheduling contract change from the sharing/media audit.
 * - Old behavior/assertion being replaced: retries were expected exactly one second after an empty active scan.
 * - Why old assertion is no longer correct: fixed one-second /24 scans violate the power/network budget requirement.
 * - Coverage preserved by: retry-after-backoff assertions still prove late hotspot peers become observable.
 * - Why this is not fitting the test to the implementation: the assertions encode the power/network budget and idempotent lifecycle contract, not private loop structure.
 */

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanShareStartupFailure
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Test Change Justification:
 * - Reason category: hotspot discovery regression contract correction.
 * - Old behavior/assertion being replaced: NSD start failure skipped active discovery entirely.
 * - Why old assertion is no longer correct: Android hotspot/local-network discovery can have NSD
 *   unavailable while direct /share/ping probing still works and must keep searching.
 * - Coverage preserved by: disabled LAN-share and no-snapshot paths still assert no phantom devices.
 * - Why this is not fitting the test to the implementation: the new contract is the user-visible
 *   hotspot behavior: real peers found by active probing must appear even if NSD does not start.
 */
/*
 * Test Change Justification:
 * - Reason category: hotspot server availability regression coverage extension.
 * - Old behavior/assertion being replaced: NSD registration failure was only covered as a hard
 *   service-start failure path.
 * - Why old assertion is no longer correct: active hotspot discovery depends on the stable HTTP
 *   /share/ping server staying available even when mDNS registration is rejected by Android.
 * - Coverage preserved by: startup/discovery failure emission and disabled LAN-share tests remain.
 * - Why this is not fitting the test to the implementation: the assertion encodes the external
 *   peer-visible contract that the stable share port must remain reachable for fallback scans.
 */
/*
 * Test Change Justification:
 * - Reason category: LAN server bind contract correction.
 * - Old behavior/assertion being replaced: lifecycle tests expected the HTTP server to bind to the
 *   selected LAN interface address.
 * - Why old assertion is no longer correct: binding to one selected address can make the stable
 *   fallback ping server unreachable when Android reports a local-network or hotspot link that is
 *   not the interface peers actually use; the server should listen on all interfaces while
 *   discovery still advertises and scans the selected LAN snapshot.
 * - Coverage preserved by: assertions still lock the selected snapshot used for active scanning
 *   and the stable port/device name used for discovery.
 * - Why this is not fitting the test to the implementation: the historical RED symptom was the
 *   server binding to the selected LAN address instead of the wildcard host, which made fallback
 *   peers unreachable on some hotspot/local-network routes.
 */
/*
 * Test Change Justification:
 * - Reason category: multi-network discovery refactor contract extension.
 * - Old behavior/assertion being replaced: lifecycle controller selected a single LAN snapshot and
 *   only scanned that snapshot's subnet, so hotspot+Wi-Fi coexistence on the same device caused the
 *   other subnet's peers to be missed.
 * - Why old assertion is no longer correct: production now enumerates every eligible LAN snapshot
 *   and fans NSD register/discover plus active probes out to each, recording the snapshot list as
 *   the active state.
 * - Coverage preserved by: original single-snapshot scenarios are kept with the resolver returning a
 *   single-element list; new tests cover the coexistence and recovery paths.
 * - Why this is not fitting the test to the implementation: the new assertions encode the externally
 *   observable contract of multi-subnet peer visibility reported by users.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareServiceLifecycleControllerActiveDiscoveryTest : DataFunSpec() {
    init {
        test("start services binds wildcard server and discovery scans selected lan snapshot") {
            `start services binds wildcard server and discovery scans selected lan snapshot`()
        }

        test("start services waits for topology and starts when lan network appears") {
            `start services waits for topology and starts when lan network appears`()
        }

        test("service registration failure keeps stable server running for active hotspot discovery") {
            `service registration failure keeps stable server running for active hotspot discovery`()
        }

        test("discovery start failure still runs active hotspot scan and reports startup failure") {
            `discovery start failure still runs active hotspot scan and reports startup failure`()
        }

        test("active discovery runs first scan immediately") { `active discovery runs first scan immediately`() }

        test("active discovery retries until hotspot peer becomes reachable") {
            `active discovery retries until hotspot peer becomes reachable`()
        }

        test("active discovery slows next scan after peer is discovered") { `active discovery slows next scan after peer is discovered`() }

        test("redundant start discovery preserves active scan backoff policy") {
            `redundant start discovery preserves active scan backoff policy`()
        }

        test("restart discovery resets active scan backoff policy") { `restart discovery resets active scan backoff policy`() }

        test("stop services cancels in flight active discovery scan") { `stop services cancels in flight active discovery scan`() }

        test("disabled lan share skips service startup and discovery") { `disabled lan share skips service startup and discovery`() }

        test("eligible snapshots include both wifi and hotspot when host shares both subnets") {
            `eligible snapshots include both wifi and hotspot when host shares both subnets`()
        }

        test("nsd registration falls back when only some snapshots accept the listener") {
            `nsd registration falls back when only some snapshots accept the listener`()
        }
    }

    private fun `start services binds wildcard server and discovery scans selected lan snapshot`() =
        runTest {
            val device = DiscoveredDevice(name = "Peer", host = "192.168.1.42", port = LAN_SHARE_DISCOVERY_PORT)
            val discovery = RecordingLanShareDiscoveryCoordinator()
            val scanner = RecordingLanShareActiveDiscoveryScanner(scanResults = listOf(listOf(device)))
            val server = RecordingLomoShareServerRuntime(boundPort = LAN_SHARE_DISCOVERY_PORT)
            val controller =
                createController(
                    scope = this,
                    discovery = discovery,
                    scanner = scanner,
                    server = server,
                    snapshot = LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.37"),
                )

            controller.startServices()
            testScheduler.advanceUntilIdle()
            controller.startDiscovery()
            runCurrent()

            server.startedPort shouldBe LAN_SHARE_DISCOVERY_PORT
            server.startedHost shouldBe "0.0.0.0"
            discovery.registeredPort shouldBe LAN_SHARE_DISCOVERY_PORT
            discovery.registeredDeviceName shouldBe "Pixel"
            scanner.scanSnapshots shouldBe listOf(LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.37"))
            discovery.mergedDevices shouldBe listOf(device)
            controller.stopDiscovery()
        }

    private fun `start services waits for topology and starts when lan network appears`() =
        runTest {
            val networkCallback = slot<ConnectivityManager.NetworkCallback>()
            val connectivityManager =
                mockk<ConnectivityManager> {
                    every { registerDefaultNetworkCallback(capture(networkCallback)) } just Runs
                    every { unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
                }
            val discovery = RecordingLanShareDiscoveryCoordinator()
            val scanner = RecordingLanShareActiveDiscoveryScanner(scanResults = listOf(emptyList()))
            val server = RecordingLomoShareServerRuntime(boundPort = LAN_SHARE_DISCOVERY_PORT)
            val hotspotSnapshot =
                LanShareActiveNetworkSnapshot(
                    networkKey = "hotspot-local",
                    bindHost = "192.168.43.1",
                    network = mockk(),
                )
            var snapshots = emptyList<LanShareActiveNetworkSnapshot>()
            val controller =
                createController(
                    scope = this,
                    connectivityManager = connectivityManager,
                    discovery = discovery,
                    scanner = scanner,
                    server = server,
                    snapshotsProvider = { snapshots },
                )

            controller.startServices()
            runCurrent()

            server.startCount shouldBe 0
            networkCallback.isCaptured shouldBe true

            snapshots = listOf(hotspotSnapshot)
            networkCallback.captured.onAvailable(mockk())
            testScheduler.advanceTimeBy(NETWORK_RESTART_DEBOUNCE_MS)
            runCurrent()

            server.startCount shouldBe 1
            server.startedHost shouldBe "0.0.0.0"
            discovery.registeredNetworkKeys shouldBe listOf(LAN_SHARE_GLOBAL_NSD_NETWORK_KEY, "hotspot-local")
            controller.stopServices()
        }

    private fun `service registration failure keeps stable server running for active hotspot discovery`() =
        runTest {
            val discovery = RecordingLanShareDiscoveryCoordinator(registrationAccepted = false)
            val scanner = RecordingLanShareActiveDiscoveryScanner(scanResults = listOf(emptyList()))
            val server = RecordingLomoShareServerRuntime(boundPort = LAN_SHARE_DISCOVERY_PORT)
            val controller =
                createController(
                    scope = this,
                    discovery = discovery,
                    scanner = scanner,
                    server = server,
                    snapshot = LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1"),
                )
            val failures = mutableListOf<LanShareStartupFailure>()
            val collectJob =
                backgroundScope.launch(Dispatchers.Unconfined) {
                    controller.lanShareStartupFailures.collect(failures::add)
                }

            controller.startServices()
            runCurrent()
            controller.startDiscovery()
            runCurrent()

            server.startCount shouldBe 1
            server.stopCount shouldBe 0
            server.startedPort shouldBe LAN_SHARE_DISCOVERY_PORT
            server.startedHost shouldBe "0.0.0.0"
            scanner.scanSnapshots shouldBe listOf(LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1"))
            (failures.contains(LanShareStartupFailure.ServiceRegistrationFailed)).shouldBeTrue()
            controller.stopServices()
            collectJob.cancel()
        }

    private fun `discovery start failure still runs active hotspot scan and reports startup failure`() =
        runTest {
            val device = DiscoveredDevice(name = "Peer", host = "192.168.43.8", port = LAN_SHARE_DISCOVERY_PORT)
            val discovery = RecordingLanShareDiscoveryCoordinator(discoveryAccepted = false)
            val scanner = RecordingLanShareActiveDiscoveryScanner(scanResults = listOf(listOf(device)))
            val controller =
                createController(
                    scope = this,
                    discovery = discovery,
                    scanner = scanner,
                    snapshot = LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1"),
                )
            val failures = mutableListOf<LanShareStartupFailure>()
            val collectJob =
                backgroundScope.launch(Dispatchers.Unconfined) {
                    controller.lanShareStartupFailures.collect(failures::add)
                }

            controller.startDiscovery()
            runCurrent()

            scanner.scanSnapshots shouldBe listOf(LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1"))
            discovery.mergedDevices shouldBe listOf(device)
            (failures.contains(LanShareStartupFailure.DiscoveryStartFailed)).shouldBeTrue()
            controller.stopDiscovery()
            collectJob.cancel()
        }

    private fun `active discovery runs first scan immediately`() =
        runTest {
            val discovery = RecordingLanShareDiscoveryCoordinator()
            val scanner = RecordingLanShareActiveDiscoveryScanner(scanResults = listOf(emptyList()))
            val controller =
                createController(
                    scope = this,
                    discovery = discovery,
                    scanner = scanner,
                    snapshot = LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1"),
                )

            controller.startDiscovery()
            runCurrent()

            testScheduler.currentTime shouldBe 0L
            scanner.scanSnapshots shouldBe listOf(LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1"))
            discovery.mergedDevices shouldBe emptyList<DiscoveredDevice>()
            controller.stopDiscovery()
        }

    private fun `active discovery retries until hotspot peer becomes reachable`() =
        runTest {
            val device = DiscoveredDevice(name = "Late peer", host = "192.168.43.9", port = LAN_SHARE_DISCOVERY_PORT)
            val discovery = RecordingLanShareDiscoveryCoordinator()
            val scanner =
                RecordingLanShareActiveDiscoveryScanner(
                    scanResults = listOf(
                        emptyList(),
                        listOf(device),
                    ),
                )
            val controller =
                createController(
                    scope = this,
                    discovery = discovery,
                    scanner = scanner,
                    snapshot = LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1"),
                )

            controller.startDiscovery()
            runCurrent()
            discovery.mergedDevices shouldBe emptyList<DiscoveredDevice>()

            testScheduler.advanceTimeBy(LEGACY_ACTIVE_DISCOVERY_RETRY_DELAY_MS)
            runCurrent()

            scanner.scanSnapshots.size shouldBe 1
            discovery.mergedDevices shouldBe emptyList<DiscoveredDevice>()

            testScheduler.advanceTimeBy(EMPTY_SCAN_BACKOFF_DELAY_MS - LEGACY_ACTIVE_DISCOVERY_RETRY_DELAY_MS)
            runCurrent()

            discovery.mergedDevices shouldBe listOf(device)
            scanner.scanSnapshots.size shouldBe 2
            controller.stopDiscovery()
        }

    private fun `active discovery slows next scan after peer is discovered`() =
        runTest {
            val device = DiscoveredDevice(name = "Found peer", host = "192.168.43.10", port = LAN_SHARE_DISCOVERY_PORT)
            val discovery = RecordingLanShareDiscoveryCoordinator()
            val scanner =
                RecordingLanShareActiveDiscoveryScanner(
                    scanResults =
                        listOf(
                            listOf(device),
                            emptyList(),
                        ),
                )
            val controller =
                createController(
                    scope = this,
                    discovery = discovery,
                    scanner = scanner,
                    snapshot = LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1"),
                )

            controller.startDiscovery()
            runCurrent()

            discovery.mergedDevices shouldBe listOf(device)
            scanner.scanSnapshots.size shouldBe 1

            testScheduler.advanceTimeBy(LEGACY_ACTIVE_DISCOVERY_RETRY_DELAY_MS)
            runCurrent()

            scanner.scanSnapshots.size shouldBe 1

            testScheduler.advanceTimeBy(DISCOVERED_DEVICE_SCAN_DELAY_MS - LEGACY_ACTIVE_DISCOVERY_RETRY_DELAY_MS)
            runCurrent()

            scanner.scanSnapshots.size shouldBe 2
            controller.stopDiscovery()
        }

    private fun `redundant start discovery preserves active scan backoff policy`() =
        runTest {
            val discovery = RecordingLanShareDiscoveryCoordinator()
            val scanner = RecordingLanShareActiveDiscoveryScanner(scanResults = listOf(emptyList()))
            val controller =
                createController(
                    scope = this,
                    discovery = discovery,
                    scanner = scanner,
                    snapshot = LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1"),
                )

            controller.startDiscovery()
            runCurrent()
            scanner.scanSnapshots.size shouldBe 1

            controller.startDiscovery()
            runCurrent()

            scanner.scanSnapshots.size shouldBe 1
            discovery.startDiscoveryCount shouldBe 1

            testScheduler.advanceTimeBy(LEGACY_ACTIVE_DISCOVERY_RETRY_DELAY_MS)
            runCurrent()
            scanner.scanSnapshots.size shouldBe 1

            testScheduler.advanceTimeBy(EMPTY_SCAN_BACKOFF_DELAY_MS - LEGACY_ACTIVE_DISCOVERY_RETRY_DELAY_MS)
            runCurrent()
            scanner.scanSnapshots.size shouldBe 2
            controller.stopDiscovery()
        }

    private fun `restart discovery resets active scan backoff policy`() =
        runTest {
            val discovery = RecordingLanShareDiscoveryCoordinator()
            val scanner = RecordingLanShareActiveDiscoveryScanner(scanResults = listOf(emptyList()))
            val controller =
                createController(
                    scope = this,
                    discovery = discovery,
                    scanner = scanner,
                    snapshot = LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1"),
                )

            controller.startDiscovery()
            runCurrent()
            scanner.scanSnapshots.size shouldBe 1

            testScheduler.advanceTimeBy(EMPTY_SCAN_BACKOFF_DELAY_MS)
            runCurrent()
            scanner.scanSnapshots.size shouldBe 2

            controller.stopDiscovery()
            runCurrent()

            controller.startDiscovery()
            runCurrent()
            scanner.scanSnapshots.size shouldBe 3

            testScheduler.advanceTimeBy(LEGACY_ACTIVE_DISCOVERY_RETRY_DELAY_MS)
            runCurrent()
            scanner.scanSnapshots.size shouldBe 3

            testScheduler.advanceTimeBy(EMPTY_SCAN_BACKOFF_DELAY_MS - LEGACY_ACTIVE_DISCOVERY_RETRY_DELAY_MS)
            runCurrent()
            scanner.scanSnapshots.size shouldBe 4
            controller.stopDiscovery()
        }

    private fun `stop services cancels in flight active discovery scan`() =
        runTest {
            val scanner = HangingLanShareActiveDiscoveryScanner()
            val controller =
                createController(
                    scope = this,
                    discovery = RecordingLanShareDiscoveryCoordinator(),
                    scanner = scanner,
                    snapshot = LanShareActiveNetworkSnapshot(networkKey = "hotspot-local", bindHost = "192.168.43.1"),
                )

            controller.startDiscovery()
            runCurrent()
            controller.stopServices()
            runCurrent()

            scanner.cancellationCount shouldBe 1
        }

    private fun `disabled lan share skips service startup and discovery`() =
        runTest {
            val discovery = RecordingLanShareDiscoveryCoordinator()
            val scanner = RecordingLanShareActiveDiscoveryScanner(scanResults = listOf(emptyList()))
            val server = RecordingLomoShareServerRuntime(boundPort = LAN_SHARE_DISCOVERY_PORT)
            val controller =
                createController(
                    scope = this,
                    lanShareEnabled = false,
                    discovery = discovery,
                    scanner = scanner,
                    server = server,
                    snapshot = LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.37"),
                )

            controller.startServices()
            controller.startDiscovery()
            testScheduler.advanceUntilIdle()

            server.startCount shouldBe 0
            discovery.startDiscoveryCount shouldBe 0
            scanner.scanSnapshots shouldBe emptyList<LanShareActiveNetworkSnapshot>()
        }

    private fun `eligible snapshots include both wifi and hotspot when host shares both subnets`() =
        runTest {
            val wifiPeer = DiscoveredDevice(name = "Wifi peer", host = "192.168.1.42", port = LAN_SHARE_DISCOVERY_PORT)
            val hotspotPeer = DiscoveredDevice(name = "Hotspot peer", host = "192.168.43.7", port = LAN_SHARE_DISCOVERY_PORT)
            val wifiSnapshot =
                LanShareActiveNetworkSnapshot(
                    networkKey = "wifi",
                    bindHost = "192.168.1.5",
                    network = mockk(),
                )
            val hotspotSnapshot =
                LanShareActiveNetworkSnapshot(
                    networkKey = "if:ap0",
                    bindHost = "192.168.43.1",
                    network = mockk(),
                )
            val discovery = RecordingLanShareDiscoveryCoordinator()
            val scanner =
                RecordingLanShareActiveDiscoveryScanner(
                    scanResults = listOf(listOf(wifiPeer, hotspotPeer)),
                    perSnapshotResults =
                        mapOf(
                            "wifi" to listOf(wifiPeer),
                            "if:ap0" to listOf(hotspotPeer),
                        ),
                )
            val controller =
                createController(
                    scope = this,
                    discovery = discovery,
                    scanner = scanner,
                    snapshots = listOf(wifiSnapshot, hotspotSnapshot),
                )

            controller.startServices()
            testScheduler.advanceUntilIdle()
            controller.startDiscovery()
            runCurrent()

            discovery.registeredNetworkKeys shouldBe listOf(LAN_SHARE_GLOBAL_NSD_NETWORK_KEY, "wifi", "if:ap0")
            discovery.startDiscoveryNetworkKeys shouldBe listOf(LAN_SHARE_GLOBAL_NSD_NETWORK_KEY, "wifi", "if:ap0")
            scanner.scanSnapshots.toSet() shouldBe setOf(wifiSnapshot, hotspotSnapshot)
            discovery.mergedDevices.toSet() shouldBe setOf(wifiPeer, hotspotPeer)
            controller.stopServices()
        }

    private fun `nsd registration falls back when only some snapshots accept the listener`() =
        runTest {
            val wifiSnapshot =
                LanShareActiveNetworkSnapshot(
                    networkKey = "wifi",
                    bindHost = "192.168.1.5",
                    network = mockk(),
                )
            val hotspotSnapshot =
                LanShareActiveNetworkSnapshot(
                    networkKey = "if:ap0",
                    bindHost = "192.168.43.1",
                    network = mockk(),
                )
            val discovery =
                RecordingLanShareDiscoveryCoordinator(
                    registrationRejectedKeys = setOf("if:ap0"),
                )
            val scanner = RecordingLanShareActiveDiscoveryScanner(scanResults = listOf(emptyList()))
            val server = RecordingLomoShareServerRuntime(boundPort = LAN_SHARE_DISCOVERY_PORT)
            val controller =
                createController(
                    scope = this,
                    discovery = discovery,
                    scanner = scanner,
                    server = server,
                    snapshots = listOf(wifiSnapshot, hotspotSnapshot),
                )
            val failures = mutableListOf<LanShareStartupFailure>()
            val collectJob =
                backgroundScope.launch(Dispatchers.Unconfined) {
                    controller.lanShareStartupFailures.collect(failures::add)
                }

            controller.startServices()
            testScheduler.advanceUntilIdle()

            server.startedHost shouldBe "0.0.0.0"
            discovery.registeredNetworkKeys shouldBe listOf(LAN_SHARE_GLOBAL_NSD_NETWORK_KEY, "wifi", "if:ap0")
            // Partial registration success must NOT emit the ServiceRegistrationFailed event:
            // global NSD and the wifi listener accepted, so peers can still discover us.
            failures.contains(LanShareStartupFailure.ServiceRegistrationFailed) shouldBe false
            controller.stopServices()
            collectJob.cancel()
        }

    private fun createController(
        scope: CoroutineScope,
        lanShareEnabled: Boolean = true,
        connectivityManager: ConnectivityManager? = null,
        discovery: RecordingLanShareDiscoveryCoordinator,
        scanner: LanShareActiveDiscoveryScanner,
        server: RecordingLomoShareServerRuntime = RecordingLomoShareServerRuntime(boundPort = LAN_SHARE_DISCOVERY_PORT),
        snapshot: LanShareActiveNetworkSnapshot? = null,
        snapshots: List<LanShareActiveNetworkSnapshot> = listOfNotNull(snapshot),
        snapshotsProvider: () -> List<LanShareActiveNetworkSnapshot> = { snapshots },
    ): ShareServiceLifecycleController {
        val context =
            mockk<Context>(relaxed = true) {
                every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
                every { getSystemService(Context.WIFI_SERVICE) } returns null
            }
        val pairingConfig =
            mockk<SharePairingConfig> {
                coEvery { isLanShareEnabled() } returns lanShareEnabled
                coEvery { resolveDeviceName() } returns "Pixel"
                coEvery { getEffectivePairingKeyHex() } returns null
                coEvery { isE2eEnabled() } returns false
            }
        return ShareServiceLifecycleController(
            context = context,
            pairingConfig = pairingConfig,
            scope = scope,
            discoveryCoordinator = discovery,
            activeDiscoveryScanner = scanner,
            serverRuntime = server,
            resolveEligibleSnapshots = { _, _ -> snapshotsProvider() },
        )
    }

    private class RecordingLanShareDiscoveryCoordinator(
        private val discoveryAccepted: Boolean = true,
        private val registrationAccepted: Boolean = true,
        private val registrationRejectedKeys: Set<String> = emptySet(),
    ) : LanShareDiscoveryCoordinator {
        override val discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
        var registeredPort: Int? = null
        var registeredDeviceName: String? = null
        var startDiscoveryCount = 0
        val registeredNetworkKeys = mutableListOf<String>()
        val startDiscoveryNetworkKeys = mutableListOf<String>()
        val mergedDevices = mutableListOf<DiscoveredDevice>()

        override fun registerService(
            networkKey: String,
            port: Int,
            deviceName: String,
            uuid: String,
            targetNetwork: Network?,
        ): Boolean {
            registeredPort = port
            registeredDeviceName = deviceName
            registeredNetworkKeys += networkKey
            return registrationAccepted && networkKey !in registrationRejectedKeys
        }

        override fun unregisterService(networkKey: String) = Unit

        override fun unregisterAll() = Unit

        override fun startDiscovery(
            networkKey: String,
            uuid: String,
            targetNetwork: Network?,
        ): Boolean {
            startDiscoveryCount += 1
            startDiscoveryNetworkKeys += networkKey
            return discoveryAccepted
        }

        override fun stopDiscovery(networkKey: String) = Unit

        override fun stopAllDiscovery() = Unit

        override fun mergeDiscoveredDevices(devices: List<DiscoveredDevice>) {
            mergedDevices += devices
            discoveredDevices.value = mergedDevices.toList()
        }
    }

    private class RecordingLanShareActiveDiscoveryScanner(
        private val scanResults: List<List<DiscoveredDevice>>,
        private val perSnapshotResults: Map<String, List<DiscoveredDevice>> = emptyMap(),
    ) : LanShareActiveDiscoveryScanner {
        val scanSnapshots = mutableListOf<LanShareActiveNetworkSnapshot>()

        override suspend fun scan(snapshot: LanShareActiveNetworkSnapshot): LanShareActiveDiscoveryScanResult {
            scanSnapshots += snapshot
            val devices =
                perSnapshotResults[snapshot.networkKey]
                    ?: scanResults.getOrElse(scanSnapshots.lastIndex) { scanResults.lastOrNull().orEmpty() }
            return LanShareActiveDiscoveryScanResult.routed(snapshot = snapshot, devices = devices)
        }
    }

    private class HangingLanShareActiveDiscoveryScanner : LanShareActiveDiscoveryScanner {
        var cancellationCount = 0

        override suspend fun scan(snapshot: LanShareActiveNetworkSnapshot): LanShareActiveDiscoveryScanResult =
            try {
                awaitCancellation()
            } finally {
                cancellationCount += 1
            }
    }

    private class RecordingLomoShareServerRuntime(
        private val boundPort: Int,
    ) : LomoShareServerRuntime {
        var startCount = 0
        var stopCount = 0
        var startedPort: Int? = null
        var startedHost: String? = null

        override suspend fun start(
            port: Int,
            host: String,
            deviceName: String,
        ): Int {
            startCount += 1
            startedPort = port
            startedHost = host
            return boundPort
        }

        override fun bindCallbacks(
            onIncomingPrepare: (com.lomo.domain.model.SharePayload) -> Unit,
            onSaveAttachment: suspend (name: String, type: String, payloadFile: java.io.File) -> String?,
            onDeleteAttachment: suspend (savedPath: String, type: String) -> Unit,
            onSaveMemo: suspend (content: String, timestamp: Long, attachmentMappings: Map<String, String>) -> Unit,
            getPairingKeyHex: suspend () -> String?,
            isE2eEnabled: suspend () -> Boolean,
        ) = Unit

        override fun updateDiscoveryDeviceName(deviceName: String) = Unit

        override fun stop() {
            stopCount += 1
        }

        override fun acceptIncoming() = Unit

        override fun rejectIncoming() = Unit
    }

    private companion object {
        private const val LEGACY_ACTIVE_DISCOVERY_RETRY_DELAY_MS = 1_000L
        private const val NETWORK_RESTART_DEBOUNCE_MS = 1_000L
        private const val EMPTY_SCAN_BACKOFF_DELAY_MS = 4_000L
        private const val DISCOVERED_DEVICE_SCAN_DELAY_MS = 30_000L
    }
}
