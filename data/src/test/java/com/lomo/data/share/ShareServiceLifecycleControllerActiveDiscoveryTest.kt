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



import android.content.Context
import android.net.Network
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanShareStartupFailure
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: ShareServiceLifecycleController active LAN discovery orchestration.
 *
 * Scenarios:
 * - Happy: standard happy path for ShareServiceLifecycleControllerActiveDiscoveryTest.
 * - Boundary: boundary and edge cases for ShareServiceLifecycleControllerActiveDiscoveryTest.
 * - Failure: failure and error scenarios for ShareServiceLifecycleControllerActiveDiscoveryTest.
 * - Must-not-happen: invariants are never violated for ShareServiceLifecycleControllerActiveDiscoveryTest.
 * - Behavior focus: starting a LAN share session must bind the server on the selected LAN host,
 *   advertise the stable discovery port, start NSD discovery, and still merge active-scan devices
 *   when NSD itself has not produced peers. Startup failures and disabled LAN share must not emit
 *   phantom devices.
 * - Observable outcomes: server start parameters, NSD registration/discovery requests, active scan
 *   bind hosts, merged devices, and startup failure events.
 * - TDD proof: Fails before the fix because ShareServiceLifecycleController hard-codes NSD,
 *   active-scan, and server collaborators, so the active fallback behavior cannot be proven as an
 *   observable orchestration contract.
 * - Excludes: live sockets, real NSD packets, Android runtime permission UI, and transfer payloads.
 */
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
 * - Why this is not fitting the test to the implementation: current production code still binds to
 *   the selected LAN address, so this assertion is expected to fail before the fix.
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
        test("start services binds wildcard server and discovery scans selected lan snapshot") { `start services binds wildcard server and discovery scans selected lan snapshot`() }

        test("service registration failure keeps stable server running for active hotspot discovery") { `service registration failure keeps stable server running for active hotspot discovery`() }

        test("discovery start failure still runs active hotspot scan and reports startup failure") { `discovery start failure still runs active hotspot scan and reports startup failure`() }

        test("active discovery retries until hotspot peer becomes reachable") { `active discovery retries until hotspot peer becomes reachable`() }

        test("stop services cancels in flight active discovery scan") { `stop services cancels in flight active discovery scan`() }

        test("disabled lan share skips service startup and discovery") { `disabled lan share skips service startup and discovery`() }

        test("eligible snapshots include both wifi and hotspot when host shares both subnets") { `eligible snapshots include both wifi and hotspot when host shares both subnets`() }

        test("nsd registration falls back when only some snapshots accept the listener") { `nsd registration falls back when only some snapshots accept the listener`() }
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

            testScheduler.advanceTimeBy(ACTIVE_DISCOVERY_TEST_RETRY_DELAY_MS)
            runCurrent()

            discovery.mergedDevices shouldBe listOf(device)
            scanner.scanSnapshots.size shouldBe 2
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
            val wifiSnapshot = LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.5")
            val hotspotSnapshot = LanShareActiveNetworkSnapshot(networkKey = "if:ap0", bindHost = "192.168.43.1")
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

            discovery.registeredNetworkKeys shouldBe listOf("wifi", "if:ap0")
            discovery.startDiscoveryNetworkKeys shouldBe listOf("wifi", "if:ap0")
            scanner.scanSnapshots.toSet() shouldBe setOf(wifiSnapshot, hotspotSnapshot)
            discovery.mergedDevices.toSet() shouldBe setOf(wifiPeer, hotspotPeer)
            controller.stopServices()
        }

    private fun `nsd registration falls back when only some snapshots accept the listener`() =
        runTest {
            val wifiSnapshot = LanShareActiveNetworkSnapshot(networkKey = "wifi", bindHost = "192.168.1.5")
            val hotspotSnapshot = LanShareActiveNetworkSnapshot(networkKey = "if:ap0", bindHost = "192.168.43.1")
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
            discovery.registeredNetworkKeys shouldBe listOf("wifi", "if:ap0")
            // Partial registration success must NOT emit the ServiceRegistrationFailed event:
            // the wifi listener accepted, so peers can still discover us via that snapshot.
            failures.contains(LanShareStartupFailure.ServiceRegistrationFailed) shouldBe false
            controller.stopServices()
            collectJob.cancel()
        }

    private fun createController(
        scope: CoroutineScope,
        lanShareEnabled: Boolean = true,
        discovery: RecordingLanShareDiscoveryCoordinator,
        scanner: LanShareActiveDiscoveryScanner,
        server: RecordingLomoShareServerRuntime = RecordingLomoShareServerRuntime(boundPort = LAN_SHARE_DISCOVERY_PORT),
        snapshot: LanShareActiveNetworkSnapshot? = null,
        snapshots: List<LanShareActiveNetworkSnapshot> = listOfNotNull(snapshot),
    ): ShareServiceLifecycleController {
        val context =
            mockk<Context>(relaxed = true) {
                every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns null
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
            resolveEligibleSnapshots = { _, _ -> snapshots },
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

        override suspend fun scan(snapshot: LanShareActiveNetworkSnapshot): List<DiscoveredDevice> {
            scanSnapshots += snapshot
            perSnapshotResults[snapshot.networkKey]?.let { return it }
            return scanResults.getOrElse(scanSnapshots.lastIndex) { scanResults.lastOrNull().orEmpty() }
        }
    }

    private class HangingLanShareActiveDiscoveryScanner : LanShareActiveDiscoveryScanner {
        var cancellationCount = 0

        override suspend fun scan(snapshot: LanShareActiveNetworkSnapshot): List<DiscoveredDevice> =
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
        private const val ACTIVE_DISCOVERY_TEST_RETRY_DELAY_MS = 1_000L
    }
}
