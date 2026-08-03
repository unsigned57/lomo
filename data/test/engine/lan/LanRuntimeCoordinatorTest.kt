package com.lomo.data.engine.lan

/*
 * Behavior Contract:
 * - Unit under test: LanRuntimeCoordinator.
 * - Owning layer: data Android LAN platform adapter.
 * - Priority tier: P0.
 * - Capability: publish validated Android facts, own the Rust listener lifecycle, and surface
 *   recovery work from the Rust inbox.
 *
 * Scenarios:
 * - Given local-network permission is absent, when services start, then Rust is not started, the
 *   multicast lease is not acquired, and the coordinator exposes a permission failure.
 * - Given Rust returns a session challenge and a committable item before its poll fails, when the
 *   coordinator polls, then the challenge is signed, the item is committed, and the poll failure
 *   remains observable instead of silently terminating the loop.

 * Observable outcomes: engine calls, lease ownership, signed challenge bytes, commit commands,
 * and the coordinator failure state.
 *
 * TDD proof: RED because the coordinator used a hard-coded permission=true value, did not own a
 * multicast lease, and allowed poll exceptions to terminate without an observable state. The full
 * data suite also reproduced a scheduler race when the test waited on a scheduler that did not own
 * the listener job.
 *
 * Excludes: Android framework callback registration, NSD implementation, Rust protocol semantics.
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.DiscoveredDevice
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LanRuntimeCoordinatorTest : DataFunSpec() {
    init {
        test("given permission is absent when services start then coordinator fails before Rust start") {
            val engine = FakeLanCoordinatorEngine()
            val network = FakeLanRuntimeNetworkMonitor(
                snapshot = LanPlatformNetworkSnapshot(permissionGranted = false, candidates = emptyList()),
            )
            val lease = FakeLanRuntimeMulticastLease()
            val coordinator = coordinator(engine, network, lease)

            runTest {
                coordinator.startServices("Phone") shouldBe false
            }

            engine.startCalls shouldBe 0
            lease.acquireServiceCalls shouldBe 0
            coordinator.failure.value?.operation shouldBe LanRuntimeFailureOperation.Permission
        }

        test("given poll returns work then fails when services run then work completes and failure is observable") {
            val challenge = LanSessionChallenge(
                sessionId = "session-1",
                peerDeviceId = "peer-1",
                transcriptToSign = byteArrayOf(7),
                deadlineMs = 10_000,
            )
            val engine = FakeLanCoordinatorEngine(
                inboxes = ArrayDeque(
                    listOf(
                        LanRuntimeInbox(
                            pairingChallenges = emptyList(),
                            sessionChallenges = listOf(challenge),
                            activeSessions = emptyList(),
                            pendingBatches = emptyList(),
                            batchRecoveries = emptyList(),
                            committableItems = listOf(LanCommittableItem("batch-1", 0u)),
                            outgoingBatches = emptyList(),
                        ),
                    ),
                ),
                failWhenEmpty = true,
            )
            runTest {
                val coordinator = coordinator(
                    engine = engine,
                    network = FakeLanRuntimeNetworkMonitor(
                        snapshot = LanPlatformNetworkSnapshot(
                            permissionGranted = true,
                            candidates = listOf(LanBindCandidate("192.168.1.8", 0u)),
                        ),
                    ),
                    lease = FakeLanRuntimeMulticastLease(),
                    scope = this,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )

                coordinator.startServices("Phone") shouldBe true
                advanceUntilIdle()

                engine.signedSessionIds shouldContain "session-1"
                engine.committedItems shouldContain "batch-1:0"
                coordinator.failure.value?.operation shouldBe LanRuntimeFailureOperation.Listener
            }
        }

        test("given a listening service when eligible network facts change then Rust listener rebinds") {
            val engine = FakeLanCoordinatorEngine()
            val network = FakeLanRuntimeNetworkMonitor(
                snapshot = LanPlatformNetworkSnapshot(
                    permissionGranted = true,
                    candidates = listOf(LanBindCandidate("192.168.1.8", 0u)),
                ),
            )
            val coordinator = coordinator(engine, network, FakeLanRuntimeMulticastLease())

            runTest {
                coordinator.startServices("Phone") shouldBe true
                network.emit(
                    LanPlatformNetworkSnapshot(
                        permissionGranted = true,
                        candidates = listOf(LanBindCandidate("192.168.43.1", 0u)),
                    ),
                )
                advanceUntilIdle()
                coordinator.stopServices()
            }

            engine.startCalls shouldBe 2
            engine.networkFacts.last().candidates shouldBe listOf(LanBindCandidate("192.168.43.1", 0u))
        }
    }

    private fun coordinator(
        engine: FakeLanCoordinatorEngine,
        network: FakeLanRuntimeNetworkMonitor,
        lease: FakeLanRuntimeMulticastLease,
        scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        dispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    ): LanRuntimeCoordinator =
        LanRuntimeCoordinator(
            engine = engine,
            discovery = FakeDiscoveryCoordinator(),
            deviceKey = FakeLanDeviceKey(),
            scope = scope,
            networkMonitor = network,
            multicastLease = lease,
            dispatcher = dispatcher,
            pollIntervalMillis = 1,
        )
}

private class FakeLanDeviceKey : LanDeviceKey {
    override fun publicIdentity(displayName: String): LanDeviceIdentity =
        LanDeviceIdentity(ByteArray(65) { 4 }, displayName)

    override fun sign(challenge: LanSigningChallenge): ByteArray =
        challenge.transcriptToSign + 1
}

private class FakeLanRuntimeNetworkMonitor(
    snapshot: LanPlatformNetworkSnapshot,
) : LanRuntimeNetworkMonitor {
    private var current = snapshot
    private var callback: ((LanPlatformNetworkSnapshot) -> Unit)? = null
    override fun snapshot(): LanPlatformNetworkSnapshot = current

    override fun start(onChanged: (LanPlatformNetworkSnapshot) -> Unit) {
        callback = onChanged
    }

    override fun stop() = Unit

    fun emit(snapshot: LanPlatformNetworkSnapshot) {
        current = snapshot
        callback?.invoke(snapshot)
    }
}

private class FakeLanRuntimeMulticastLease : LanRuntimeMulticastLease {
    var acquireServiceCalls = 0
    override fun acquireService() {
        acquireServiceCalls++
    }

    override fun releaseService() = Unit
    override fun acquireDiscovery() = Unit
    override fun releaseDiscovery() = Unit
}

private class FakeDiscoveryCoordinator : com.lomo.data.share.LanShareDiscoveryCoordinator {
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()
    override fun registerService(port: Int, deviceName: String, deviceId: String): Boolean = true
    override fun unregisterService() = Unit
    override fun startDiscovery(deviceId: String): Boolean = true
    override fun stopDiscovery() = Unit
    override fun mergeDiscoveredDevices(devices: List<DiscoveredDevice>) = Unit
}

private class FakeLanCoordinatorEngine(
    private val inboxes: ArrayDeque<LanRuntimeInbox> = ArrayDeque(),
    private val failWhenEmpty: Boolean = false,
) : LanCoordinatorEngine {
    var startCalls = 0
    val signedSessionIds = mutableListOf<String>()
    val committedItems = mutableListOf<String>()
    val networkFacts = mutableListOf<LanNetworkFacts>()

    override fun configureLanIdentity(identity: LanDeviceIdentity) =
        LanLocalIdentity("local-1", identity.displayName)

    override fun updateLanNetworkSnapshot(snapshot: LanNetworkFacts) {
        networkFacts += snapshot
    }
    override fun updateLanDiscoverySnapshot(snapshot: LanDiscoveryFacts) = Unit
    override fun listLanDiscoveredPeers(): List<LanDiscoveredPeer> = emptyList()
    override fun startLanService(): LanServiceState {
        startCalls++
        return LanServiceState(LanServicePhase.Listening, "192.168.1.8:1234")
    }

    override fun stopLanService() = LanServiceState(LanServicePhase.Stopped, null)
    override fun lanRuntimeInbox(): LanRuntimeInbox = inboxes.lastOrNull() ?: LanRuntimeInbox(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
    )

    override fun pollLanListener(nowMs: Long): LanRuntimeInbox =
        when {
            inboxes.isNotEmpty() -> inboxes.removeFirst()
            failWhenEmpty -> error("poll failed")
            else -> LanRuntimeInbox(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            )
        }

    override fun confirmLanSession(sessionId: String, signature: ByteArray, nowMs: Long) {
        signedSessionIds += sessionId
    }

    override fun commitReceivedLanItem(batchId: String, itemIndex: UInt, nowMs: Long): String {
        committedItems += "$batchId:$itemIndex"
        return "memo-1"
    }
}
