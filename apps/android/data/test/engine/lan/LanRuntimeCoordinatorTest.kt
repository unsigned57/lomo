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
 * - Given Rust returns a session challenge and a committable item before its inbox wait fails, when
 *   the coordinator awaits inbox generations, then the challenge is signed, the item is committed,
 *   and the wait failure remains observable instead of silently terminating the loop.
 * - Given the coordinator source is inspected, when the listener loop is read, then it waits on
 *   inbox generation instead of polling JNI every 100ms.


 * Observable outcomes: engine calls, lease ownership, signed challenge bytes, commit commands,
 * and the coordinator failure state.
 *
 * TDD proof: RED because the coordinator used a hard-coded permission=true value, did not own a
 * multicast lease, and allowed poll exceptions to terminate without an observable state. The full
 * data suite also reproduced a scheduler race when the test waited on a scheduler that did not own
 * the listener job.
 *
 * Excludes: Android framework callback registration, NSD implementation, Rust protocol semantics.
 * // architectural-boundary-check: the listener-loop assertion pins the await-based contract.
 * Test Change Justification:
 * - Reason category: behavior contract change (inbox-driven listener loop).
 * - Old behavior/assertion being replaced: the coordinator polled JNI on a 100ms interval.
 * - Why old assertion is no longer correct: the listener now awaits engine.awaitLanInbox
 *   generations; the poll loop is removed.
 * - Coverage preserved by: challenge-signing/commit/failure-visibility scenarios still assert
 *   observable outcomes; a source-level check pins the await-based loop.
 * - Why this is not fitting the test to the implementation: the await contract is the declared
 *   listener design, not an incidental detail.
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
import java.io.File

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
                coordinator.failure.value?.operation shouldBe LanRuntimeFailureOperation.ServiceFailed
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

        test("given one committable item fails when the inbox processes then the sibling still commits and the service stays up") {
            val engine = FakeLanCoordinatorEngine(
                inboxes = ArrayDeque(
                    listOf(
                        LanRuntimeInbox(
                            pairingChallenges = emptyList(),
                            sessionChallenges = emptyList(),
                            activeSessions = emptyList(),
                            pendingBatches = emptyList(),
                            batchRecoveries = emptyList(),
                            committableItems = listOf(
                                LanCommittableItem("batch-1", 0u),
                                LanCommittableItem("batch-1", 1u),
                            ),
                            outgoingBatches = emptyList(),
                        ),
                    ),
                ),
                failCommits = setOf("batch-1:0"),
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

                engine.committedItems shouldContain "batch-1:1"
                engine.failedItems shouldContain "batch-1:0"
                engine.stopCalls shouldBe 0
                coordinator.serviceState.value.phase shouldBe LanServicePhase.Listening
                coordinator.failure.value?.operation shouldBe LanRuntimeFailureOperation.ItemFailed
                coordinator.stopServices()
            }
        }

        test("given the pump reports rejected connections when services run then the failure is observable without stopping the service") {
            val engine = FakeLanCoordinatorEngine(
                inboxes = ArrayDeque(
                    listOf(
                        LanRuntimeInbox(
                            pairingChallenges = emptyList(),
                            sessionChallenges = emptyList(),
                            activeSessions = emptyList(),
                            pendingBatches = emptyList(),
                            batchRecoveries = emptyList(),
                            committableItems = emptyList(),
                            outgoingBatches = emptyList(),
                        ),
                    ),
                ),
                rejectionCounts = ArrayDeque(listOf(1uL)),
                rejectionDiagnostics = ArrayDeque(listOf("lan_frame_magic_invalid: bad magic")),
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

                engine.stopCalls shouldBe 0
                coordinator.serviceState.value.phase shouldBe LanServicePhase.Listening
                coordinator.failure.value?.operation shouldBe LanRuntimeFailureOperation.ConnectionRejected
                coordinator.stopServices()
            }
        }

        test("given a rejected NSD record when discovery runs then the rejection is observable without stopping discovery") {
            val engine = FakeLanCoordinatorEngine()
            val discovery = FakeDiscoveryCoordinator()
            runTest {
                val coordinator = LanRuntimeCoordinator(
                    engine = engine,
                    discovery = discovery,
                    deviceKey = FakeLanDeviceKey(),
                    scope = this,
                    networkMonitor = FakeLanRuntimeNetworkMonitor(
                        snapshot = LanPlatformNetworkSnapshot(
                            permissionGranted = true,
                            candidates = listOf(LanBindCandidate("192.168.1.8", 0u)),
                        ),
                    ),
                    multicastLease = FakeLanRuntimeMulticastLease(),
                    dispatcher = StandardTestDispatcher(testScheduler),
                )

                coordinator.startDiscovery("Phone") shouldBe true
                discovery.rejectNextRecord()
                advanceUntilIdle()

                discovery.stopCalls shouldBe 0
                coordinator.failure.value?.operation shouldBe LanRuntimeFailureOperation.PeerDiscoveryRejected
                coordinator.stopDiscovery()
                coordinator.stopServices()
            }
        }

        test("given coordinator source when the listener loop is inspected then it waits on inbox generation") {
            val currentDir = File(System.getProperty("user.dir") ?: ".")
            val source =
                listOf(
                    currentDir.resolve("src/engine/lan/LanRuntimeCoordinator.kt"),
                    currentDir.resolve("data/src/engine/lan/LanRuntimeCoordinator.kt"),
                ).first { file -> file.isFile }
            val text = source.readText()
            text.contains("engine.awaitLanInbox") shouldBe true
            text.contains("engine.pollLanListener") shouldBe false
            text.contains("LISTENER_POLL_INTERVAL_MS = 100") shouldBe false
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
    private val _rejectedRecordCount = MutableStateFlow(0)
    var stopCalls = 0
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()
    override val rejectedRecordCount: StateFlow<Int> = _rejectedRecordCount.asStateFlow()
    fun rejectNextRecord() {
        _rejectedRecordCount.value += 1
    }
    override fun registerService(
        port: Int,
        deviceName: String,
        deviceId: String,
        protocolVersion: UInt,
    ): Boolean = true
    override fun unregisterService() = Unit
    override fun startDiscovery(deviceId: String): Boolean = true
    override fun stopDiscovery() {
        stopCalls += 1
    }
    override fun mergeDiscoveredDevices(devices: List<DiscoveredDevice>) = Unit
}

private class FakeLanCoordinatorEngine(
    private val inboxes: ArrayDeque<LanRuntimeInbox> = ArrayDeque(),
    private val failWhenEmpty: Boolean = false,
    private val failCommits: Set<String> = emptySet(),
    private val rejectionCounts: ArrayDeque<ULong> = ArrayDeque(),
    private val rejectionDiagnostics: ArrayDeque<String?> = ArrayDeque(),
) : LanCoordinatorEngine {
    var startCalls = 0
    var stopCalls = 0
    val signedSessionIds = mutableListOf<String>()
    val committedItems = mutableListOf<String>()
    val failedItems = mutableListOf<String>()
    val networkFacts = mutableListOf<LanNetworkFacts>()
    private var waitGeneration = 0uL

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

    override fun lanProtocolLimits(): LanProtocolLimits =
        LanProtocolLimits(
            protocolVersion = 3u,
            pairingTtlMs = 120_000L,
            sessionTtlMs = 60_000L,
            approvalTtlMs = 900_000L,
        )

    override fun stopLanService(): LanServiceState {
        stopCalls += 1
        return LanServiceState(LanServicePhase.Stopped, null)
    }

    override suspend fun awaitLanInbox(lastGeneration: ULong, timeoutMs: ULong): LanInboxWait =
        when {
            inboxes.isNotEmpty() -> {
                waitGeneration += 1uL
                LanInboxWait(
                    waitGeneration,
                    inboxes.removeFirst(),
                    rejectionCounts.removeFirstOrNull() ?: 0uL,
                    rejectionDiagnostics.removeFirstOrNull(),
                )
            }
            failWhenEmpty -> error("poll failed")
            else -> kotlinx.coroutines.awaitCancellation()
        }

    override fun confirmLanSession(sessionId: String, signature: ByteArray, nowMs: Long) {
        signedSessionIds += sessionId
    }

    override fun resolveReceivedLanItem(
        batchId: String,
        itemIndex: UInt,
        resolution: LanReceivedItemResolution,
    ) {
        val key = "$batchId:$itemIndex"
        when (resolution) {
            is LanReceivedItemResolution.Commit -> {
                if (key in failCommits) {
                    throw IllegalStateException("store rejected the item")
                }
                committedItems += key
            }
            is LanReceivedItemResolution.Fail -> failedItems += key
        }
    }
}
