package com.lomo.data.engine.lan

/*
 * Behavior Contract:
 * - Unit under test: RustLanShareService passive peer projection.
 * - Owning layer: data LAN share boundary.
 * - Priority tier: P0.
 * - Capability: trusted peers are a projection of engine-durable state. Before any native
 *   adapter exists (cold start, engine not yet requested or still opening) the only honest
 *   projection is empty — a passive refresh must not demand the adapter. Command paths keep
 *   failing closed through withActiveEngineAdapter.
 *
 * Scenarios:
 * - Given a ManagedEngineSession whose engine was never started (no adapter), when the service
 *   is constructed and the runtime inbox emits its initial snapshot, then the collector
 *   completes without exception and trusted peers stay empty.
 * - Given an engine that can serve listLanPeers, when the same initial emission arrives, then
 *   trusted peers project the engine page (revoked entries filtered).
 *
 * Observable outcomes: trustedPeers/incomingBatches values; absence of an uncaught coroutine
 * exception (pre-fix the refresh threw IllegalStateException on the appScope collector,
 * taking the whole process down — the emulator launch crash behind the B-19b evidence gap).
 *
 * TDD proof:
 * - RED command: ./kotlin test --include-module=data
 *   --include-classes='com.lomo.data.engine.lan.RustLanShareServicePeerRefreshGuardTest'
 * - RED symptom: the first scenario dies inside the init collector with
 *   IllegalStateException("Managed engine session has no active adapter") — the same
 *   production crash logcat shows for com.lomo.app at startup.
 *
 * Excludes:
 * - NSD discovery, WorkManager, Keystore signing, chunk transmission and workspace readiness
 *   transitions; the runtime coordinator is exercised only through its initial StateFlow
 *   emission, exactly what production init does.
 */

import com.lomo.data.engine.CandidateOpenContext
import com.lomo.data.engine.CapabilityRegistry
import com.lomo.data.engine.ManagedEngineSession
import com.lomo.data.engine.SessionDateFormatSource
import com.lomo.data.local.datastore.LomoLanSharePreferencesStore
import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.data.share.LanShareDiscoveryCoordinator
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanTrustedPeer
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.usecase.SingleDispatcherProvider
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

private class PeerGuardPreferences : LomoLanSharePreferencesStore {
    override val lanShareEnabled = MutableStateFlow(false)
    override val lanShareDeviceName = MutableStateFlow<String?>("test-device")
    override val shareCardShowTime = MutableStateFlow(false)
    override val shareCardShowBrand = MutableStateFlow(false)
    override val shareCardSignatureText = MutableStateFlow("")
    override val syncInboxEnabled = MutableStateFlow(false)

    override suspend fun updateLanShareEnabled(enabled: Boolean) {
        lanShareEnabled.value = enabled
    }

    override suspend fun updateLanShareDeviceName(name: String?) {
        lanShareDeviceName.value = name
    }

    override suspend fun updateShareCardShowTime(enabled: Boolean) {
        shareCardShowTime.value = enabled
    }

    override suspend fun updateShareCardShowBrand(enabled: Boolean) {
        shareCardShowBrand.value = enabled
    }

    override suspend fun updateShareCardSignatureText(text: String) {
        shareCardSignatureText.value = text
    }

    override suspend fun updateSyncInboxEnabled(enabled: Boolean) {
        syncInboxEnabled.value = enabled
    }
}

/** Identity/signature stub — no Keystore in JVM tests; never exercised by the init path. */
private object PeerGuardDeviceKey : LanDeviceKey {
    override fun publicIdentity(displayName: String) =
        LanDeviceIdentity(publicKey = ByteArray(65) { 1 }, displayName = displayName)

    override fun sign(challenge: LanSigningChallenge): ByteArray = byteArrayOf(7)
}

/** Engine facts never reach the coordinator in these scenarios: services stay stopped. */
private object PeerGuardCoordinatorEngine : LanCoordinatorEngine {
    override fun configureLanIdentity(identity: LanDeviceIdentity): LanLocalIdentity =
        LanLocalIdentity(deviceId = "self", displayName = identity.displayName)

    override fun updateLanNetworkSnapshot(snapshot: LanNetworkFacts) = Unit

    override fun updateLanDiscoverySnapshot(snapshot: LanDiscoveryFacts) = Unit

    override fun listLanDiscoveredPeers(): List<LanDiscoveredPeer> = emptyList()

    override fun startLanService(): LanServiceState = LanServiceState(LanServicePhase.Stopped, null)

    override fun stopLanService(): LanServiceState = LanServiceState(LanServicePhase.Stopped, null)

    override fun lanProtocolLimits(): LanProtocolLimits =
        LanProtocolLimits(
            protocolVersion = 1u,
            pairingTtlMs = 1L,
            sessionTtlMs = 1L,
            approvalTtlMs = 1L,
        )

    override suspend fun awaitLanInbox(lastGeneration: ULong, timeoutMs: ULong): LanInboxWait =
        error("inbox polling is not started in these scenarios")

    override fun confirmLanSession(sessionId: String, signature: ByteArray, nowMs: Long) = Unit

    override fun resolveReceivedLanItem(batchId: String, itemIndex: UInt, resolution: LanReceivedItemResolution) =
        Unit
}

private class PeerGuardDiscoveryCoordinator : LanShareDiscoveryCoordinator {
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = MutableStateFlow(emptyList())
    override val rejectedRecordCount: StateFlow<Int> = MutableStateFlow(0)

    override fun registerService(port: Int, deviceName: String, deviceId: String, protocolVersion: UInt) = true

    override fun unregisterService() = Unit

    override fun startDiscovery(deviceId: String) = true

    override fun stopDiscovery() = Unit

    override fun mergeDiscoveredDevices(devices: List<DiscoveredDevice>) = Unit
}

private object PeerGuardNetworkMonitor : LanRuntimeNetworkMonitor {
    override fun snapshot() =
        LanPlatformNetworkSnapshot(permissionGranted = false, candidates = emptyList())

    override fun start(onChanged: (LanPlatformNetworkSnapshot) -> Unit) = Unit

    override fun stop() = Unit
}

private object PeerGuardMulticastLease : LanRuntimeMulticastLease {
    override fun acquireService() = Unit

    override fun releaseService() = Unit

    override fun acquireDiscovery() = Unit

    override fun releaseDiscovery() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class RustLanShareServicePeerRefreshGuardTest : DataFunSpec() {
    init {

        test("given no engine adapter when the service starts then passive refresh leaves trusted peers empty") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("lan-share-no-adapter").toFile()
                try {
                    val lane = UnconfinedTestDispatcher(testScheduler)
                    val session =
                        ManagedEngineSession(
                            candidateOpen =
                                CandidateOpenContext(
                                    filesDir = filesDir,
                                    openAdapter = { error("engine must never open in this scenario") },
                                    isContentUri = { false },
                                    sessionDateFormat =
                                        SessionDateFormatSource {
                                            StorageFilenameFormats.DEFAULT_PATTERN
                                        },
                                ),
                            capabilityRegistry = CapabilityRegistry(),
                            directorySettingsRepository = mockk<DirectorySettingsRepository>(relaxed = true),
                            appScope = backgroundScope,
                            invalidation = StoreInvalidationBus(),
                        )
                    val runtime =
                        LanRuntimeCoordinator(
                            engine = PeerGuardCoordinatorEngine,
                            discovery = PeerGuardDiscoveryCoordinator(),
                            deviceKey = PeerGuardDeviceKey,
                            scope = backgroundScope,
                            networkMonitor = PeerGuardNetworkMonitor,
                            multicastLease = PeerGuardMulticastLease,
                            clockMillis = { 0L },
                            dispatcher = lane,
                        )
                    val service =
                        RustLanShareService(
                            context = mockk(relaxed = true),
                            preferences = PeerGuardPreferences(),
                            engine = session,
                            runtime = runtime,
                            deviceKey = PeerGuardDeviceKey,
                            appScope = backgroundScope,
                            dispatcherProvider = SingleDispatcherProvider(lane),
                        )

                    advanceUntilIdle()

                    service.trustedPeers.value.shouldBeEmpty()
                    service.incomingBatches.value.shouldBeEmpty()
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given an active engine adapter when the service starts then trusted peers project the engine page") {
            runTest {
                val lane = UnconfinedTestDispatcher(testScheduler)
                val session = mockk<ManagedEngineSession>()
                every { session.hasActiveEngineAdapter() } returns true
                every { session.listLanPeers() } returns
                    LanPeerPage(
                        peers =
                            listOf(
                                LanPeer(
                                    deviceId = "desk-1",
                                    displayName = "Desk",
                                    publicKey = ByteArray(65) { 3 },
                                    pairedAtMs = 42L,
                                    revoked = false,
                                    revokedAtMs = null,
                                ),
                                LanPeer(
                                    deviceId = "revoked-1",
                                    displayName = "Old",
                                    publicKey = ByteArray(65) { 4 },
                                    pairedAtMs = 7L,
                                    revoked = true,
                                    revokedAtMs = 9L,
                                ),
                            ),
                        total = 2u,
                    )
                val runtime =
                    LanRuntimeCoordinator(
                        engine = PeerGuardCoordinatorEngine,
                        discovery = PeerGuardDiscoveryCoordinator(),
                        deviceKey = PeerGuardDeviceKey,
                        scope = backgroundScope,
                        networkMonitor = PeerGuardNetworkMonitor,
                        multicastLease = PeerGuardMulticastLease,
                        clockMillis = { 0L },
                        dispatcher = lane,
                    )
                val service =
                    RustLanShareService(
                        context = mockk(relaxed = true),
                        preferences = PeerGuardPreferences(),
                        engine = session,
                        runtime = runtime,
                        deviceKey = PeerGuardDeviceKey,
                        appScope = backgroundScope,
                        dispatcherProvider = SingleDispatcherProvider(lane),
                    )

                advanceUntilIdle()

                service.trustedPeers.value shouldBe
                    listOf(LanTrustedPeer("desk-1", "Desk", 42L))
            }
        }
    }
}
