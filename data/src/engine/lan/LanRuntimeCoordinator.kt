package com.lomo.data.engine.lan

import android.content.Context
import com.lomo.data.engine.ManagedEngineSession
import com.lomo.data.share.LanShareDiscoveryCoordinator
import com.lomo.data.share.LanShareMulticastLockLease
import com.lomo.data.share.LanShareMulticastLockManager
import com.lomo.data.share.LanShareMulticastLockOwner
import com.lomo.data.share.NsdDiscoveryService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Public lifecycle boundary used by the share controller. */
interface LanRuntimePlatform {
    suspend fun startServices(displayName: String): Boolean

    suspend fun startDiscovery(displayName: String): Boolean

    fun stopServices()

    fun stopDiscovery()
}

internal enum class LanRuntimeFailureOperation {
    Permission,
    Topology,
    Network,
    Service,
    Discovery,
    Listener,
}

internal data class LanRuntimeFailure(
    val operation: LanRuntimeFailureOperation,
    val diagnostic: String,
)

internal data class LanPlatformNetworkSnapshot(
    val permissionGranted: Boolean,
    val candidates: List<LanBindCandidate>,
)

internal interface LanRuntimeNetworkMonitor {
    fun snapshot(): LanPlatformNetworkSnapshot

    fun start(onChanged: (LanPlatformNetworkSnapshot) -> Unit)

    fun stop()
}

internal interface LanRuntimeMulticastLease {
    fun acquireService()

    fun releaseService()

    fun acquireDiscovery()

    fun releaseDiscovery()
}

internal interface LanCoordinatorEngine {
    fun configureLanIdentity(identity: LanDeviceIdentity): LanLocalIdentity

    fun updateLanNetworkSnapshot(snapshot: LanNetworkFacts)

    fun updateLanDiscoverySnapshot(snapshot: LanDiscoveryFacts)

    fun listLanDiscoveredPeers(): List<LanDiscoveredPeer>

    fun startLanService(): LanServiceState

    fun stopLanService(): LanServiceState

    fun pollLanListener(nowMs: Long): LanRuntimeInbox

    fun lanRuntimeInbox(): LanRuntimeInbox

    fun confirmLanSession(sessionId: String, signature: ByteArray, nowMs: Long)

    fun commitReceivedLanItem(batchId: String, itemIndex: UInt, nowMs: Long): String
}

private class ManagedLanCoordinatorEngine(
    private val engine: ManagedEngineSession,
) : LanCoordinatorEngine {
    override fun configureLanIdentity(identity: LanDeviceIdentity): LanLocalIdentity =
        engine.configureLanIdentity(identity)

    override fun updateLanNetworkSnapshot(snapshot: LanNetworkFacts) =
        engine.updateLanNetworkSnapshot(snapshot)

    override fun updateLanDiscoverySnapshot(snapshot: LanDiscoveryFacts) =
        engine.updateLanDiscoverySnapshot(snapshot)

    override fun listLanDiscoveredPeers(): List<LanDiscoveredPeer> =
        engine.listLanDiscoveredPeers()

    override fun startLanService(): LanServiceState = engine.startLanService()

    override fun stopLanService(): LanServiceState = engine.stopLanService()

    override fun pollLanListener(nowMs: Long): LanRuntimeInbox = engine.pollLanListener(nowMs)

    override fun lanRuntimeInbox(): LanRuntimeInbox = engine.lanRuntimeInbox()

    override fun confirmLanSession(sessionId: String, signature: ByteArray, nowMs: Long) =
        engine.confirmLanSession(sessionId, signature, nowMs)

    override fun commitReceivedLanItem(batchId: String, itemIndex: UInt, nowMs: Long): String =
        engine.commitReceivedLanItem(batchId, itemIndex, nowMs)
}

private class AndroidLanRuntimeMulticastLease(
    context: Context,
) : LanRuntimeMulticastLease {
    private val manager =
        LanShareMulticastLockManager(
            context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager,
        )
    private val lease =
        LanShareMulticastLockLease(
            acquireLock = manager::acquire,
            releaseLock = manager::releaseIfHeld,
        )

    override fun acquireService() = lease.acquire(LanShareMulticastLockOwner.Service)

    override fun releaseService() = lease.release(LanShareMulticastLockOwner.Service)

    override fun acquireDiscovery() = lease.acquire(LanShareMulticastLockOwner.Discovery)

    override fun releaseDiscovery() = lease.release(LanShareMulticastLockOwner.Discovery)
}

/**
 * Android platform adapter around the Rust LAN owner.
 *
 * Android supplies only validated permission/network/NSD facts and Keystore signatures. Rust owns
 * identity, pairing, session, batch, chunk, commit and recovery truth.
 */
internal class LanRuntimeCoordinator internal constructor(
    private val engine: LanCoordinatorEngine,
    private val discovery: LanShareDiscoveryCoordinator,
    private val deviceKey: LanDeviceKey,
    private val scope: CoroutineScope,
    private val networkMonitor: LanRuntimeNetworkMonitor,
    private val multicastLease: LanRuntimeMulticastLease,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pollIntervalMillis: Long = LISTENER_POLL_INTERVAL_MS,
) : LanRuntimePlatform, AutoCloseable {
    internal constructor(
        context: Context,
        engine: ManagedEngineSession,
        discovery: NsdDiscoveryService,
        deviceKey: LanDeviceKey,
        scope: CoroutineScope,
    ) : this(
        engine = ManagedLanCoordinatorEngine(engine),
        discovery = discovery,
        deviceKey = deviceKey,
        scope = scope,
        networkMonitor = AndroidLanRuntimeNetworkMonitor(context),
        multicastLease = AndroidLanRuntimeMulticastLease(context),
    )

    private val revision = AtomicLong(0)
    private val reconcileMutex = Mutex()
    private val _inbox = MutableStateFlow(emptyRuntimeInbox())
    private val _discoveredPeers = MutableStateFlow<List<LanDiscoveredPeer>>(emptyList())
    private val _serviceState = MutableStateFlow(LanServiceState(LanServicePhase.Stopped, null))
    private val _failure = MutableStateFlow<LanRuntimeFailure?>(null)
    private var localIdentity: LanLocalIdentity? = null
    private var listenerJob: Job? = null
    private var discoveryJob: Job? = null
    private var servicesDesired = false
    private var discoveryDesired = false
    private var serviceStarted = false
    private var discoveryStarted = false
    private var publishedNetwork: LanPlatformNetworkSnapshot? = null
    private var serviceNetwork: LanPlatformNetworkSnapshot? = null
    private var discoveryNetwork: LanPlatformNetworkSnapshot? = null

    val inbox: StateFlow<LanRuntimeInbox> = _inbox.asStateFlow()
    val discoveredPeers: StateFlow<List<LanDiscoveredPeer>> = _discoveredPeers.asStateFlow()
    val serviceState: StateFlow<LanServiceState> = _serviceState.asStateFlow()
    val failure: StateFlow<LanRuntimeFailure?> = _failure.asStateFlow()

    override suspend fun startServices(displayName: String): Boolean {
        servicesDesired = true
        return try {
            ensureIdentity(displayName)
            ensureNetworkMonitor()
            reconcileMutex.withLock { reconcileService(displayName) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportFailure(LanRuntimeFailureOperation.Service, error.lanRuntimeDiagnostic())
            false
        }
    }

    override suspend fun startDiscovery(displayName: String): Boolean {
        discoveryDesired = true
        return try {
            ensureIdentity(displayName)
            ensureNetworkMonitor()
            reconcileMutex.withLock { reconcileDiscovery() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportFailure(LanRuntimeFailureOperation.Discovery, error.lanRuntimeDiagnostic())
            false
        }
    }

    override fun stopServices() {
        servicesDesired = false
        discoveryDesired = false
        stopListener()
        stopActualDiscovery()
        stopActualService()
        networkMonitor.stop()
        publishedNetwork = null
        _failure.value = null
    }

    override fun stopDiscovery() {
        discoveryDesired = false
        stopActualDiscovery()
        if (!servicesDesired) {
            networkMonitor.stop()
            publishedNetwork = null
        }
    }

    override fun close() = stopServices()

    private fun ensureIdentity(displayName: String) {
        if (localIdentity == null) {
            localIdentity = engine.configureLanIdentity(deviceKey.publicIdentity(displayName))
        }
    }

    private fun ensureNetworkMonitor() {
        if (publishedNetwork == null) {
            networkMonitor.start { snapshot ->
                publishedNetwork = snapshot
                scope.launch(dispatcher) {
                    try {
                        reconcileMutex.withLock {
                            engine.publishNetwork(revision, snapshot)
                            if (servicesDesired) reconcileService(localIdentity?.displayName.orEmpty())
                            if (discoveryDesired) reconcileDiscovery()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        reportFailure(LanRuntimeFailureOperation.Network, error.lanRuntimeDiagnostic())
                    }
                }
            }
            publishedNetwork = networkMonitor.snapshot()
        }
    }

    private fun reconcileService(displayName: String): Boolean {
        val snapshot = networkMonitor.snapshot()
        engine.publishNetwork(revision, snapshot)
        return when {
            !snapshot.permissionGranted -> {
                stopActualService()
                reportFailure(LanRuntimeFailureOperation.Permission, "local-network permission is not granted")
                false
            }
            snapshot.candidates.isEmpty() -> {
                stopActualService()
                reportFailure(LanRuntimeFailureOperation.Topology, "no eligible LAN network candidate is available")
                false
            }
            serviceStarted && serviceNetwork == snapshot -> true
            else -> {
                if (serviceStarted) stopActualService()
                multicastLease.acquireService()
                try {
                    val state = engine.startLanService()
                    _serviceState.value = state
                    check(state.phase == LanServicePhase.Listening) {
                        "Rust LAN listener did not enter Listening"
                    }
                    serviceStarted = true
                    serviceNetwork = snapshot
                    val port = state.listenAddress?.substringAfterLast(':')?.toIntOrNull()
                        ?: error("Rust LAN listener did not return a numeric port")
                    discovery.registerService(
                        port = port,
                        deviceName = displayName,
                        deviceId = checkNotNull(localIdentity).deviceId,
                    )
                    startListenerPolling()
                    _failure.value = null
                    true
                } catch (error: Exception) {
                    multicastLease.releaseService()
                    reportFailure(LanRuntimeFailureOperation.Service, error.lanRuntimeDiagnostic())
                    false
                }
            }
        }
    }

    private fun reconcileDiscovery(): Boolean {
        val snapshot = networkMonitor.snapshot()
        engine.publishNetwork(revision, snapshot)
        return when {
            !snapshot.permissionGranted -> {
                stopActualDiscovery()
                reportFailure(LanRuntimeFailureOperation.Permission, "local-network permission is not granted")
                false
            }
            snapshot.candidates.isEmpty() -> {
                stopActualDiscovery()
                reportFailure(LanRuntimeFailureOperation.Topology, "no eligible LAN network candidate is available")
                false
            }
            discoveryStarted && discoveryNetwork == snapshot -> true
            else -> {
                if (discoveryStarted) stopActualDiscovery()
                multicastLease.acquireDiscovery()
                try {
                    check(discovery.startDiscovery(checkNotNull(localIdentity).deviceId)) {
                        "NSD discovery did not start"
                    }
                    discoveryStarted = true
                    discoveryNetwork = snapshot
                    startDiscoveryPolling()
                    _failure.value = null
                    true
                } catch (error: Exception) {
                    multicastLease.releaseDiscovery()
                    reportFailure(LanRuntimeFailureOperation.Discovery, error.lanRuntimeDiagnostic())
                    false
                }
            }
        }
    }

    private fun startListenerPolling() {
        if (listenerJob != null) return
        listenerJob = scope.launch(dispatcher) {
            try {
                while (isActive) {
                    val observed = engine.pollLanListener(clockMillis())
                    publishInboxAndProcess(observed)
                    delay(pollIntervalMillis)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure(LanRuntimeFailureOperation.Listener, error.lanRuntimeDiagnostic())
            }
        }
    }

    private fun startDiscoveryPolling() {
        if (discoveryJob != null) return
        discoveryJob = scope.launch(dispatcher) {
            try {
                discovery.discoveredDevices.collect {
                    _discoveredPeers.value = publishDiscoverySnapshot(engine, discovery, revision)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure(LanRuntimeFailureOperation.Discovery, error.lanRuntimeDiagnostic())
            }
        }
    }

    private fun publishInboxAndProcess(observed: LanRuntimeInbox) {
        _inbox.value = observed
        observed.sessionChallenges.forEach { challenge ->
            engine.confirmLanSession(
                challenge.sessionId,
                deviceKey.sign(challenge),
                clockMillis(),
            )
        }
        observed.committableItems.forEach { item ->
            engine.commitReceivedLanItem(item.batchId, item.itemIndex, clockMillis())
        }
        _inbox.value = engine.lanRuntimeInbox()
    }

    private fun stopListener() {
        listenerJob?.cancel()
        listenerJob = null
    }

    private fun stopActualService() {
        stopListener()
        if (serviceStarted || _serviceState.value.phase != LanServicePhase.Stopped) {
            _serviceState.value = engine.stopLanService()
        }
        if (serviceStarted) multicastLease.releaseService()
        serviceStarted = false
        serviceNetwork = null
        discovery.unregisterService()
    }

    private fun stopActualDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        if (discoveryStarted) {
            discovery.stopDiscovery()
            multicastLease.releaseDiscovery()
        }
        discoveryStarted = false
        discoveryNetwork = null
    }

    private fun reportFailure(operation: LanRuntimeFailureOperation, diagnostic: String) {
        _failure.value = LanRuntimeFailure(operation, diagnostic)
        when (operation) {
            LanRuntimeFailureOperation.Discovery -> stopActualDiscovery()
            LanRuntimeFailureOperation.Permission,
            LanRuntimeFailureOperation.Topology,
            LanRuntimeFailureOperation.Network -> {
                stopActualService()
                stopActualDiscovery()
            }
            LanRuntimeFailureOperation.Service,
            LanRuntimeFailureOperation.Listener -> stopActualService()
        }
    }

    private companion object {
        const val LISTENER_POLL_INTERVAL_MS = 100L
    }
}

private fun LanCoordinatorEngine.publishNetwork(
    revision: AtomicLong,
    snapshot: LanPlatformNetworkSnapshot,
) {
    updateLanNetworkSnapshot(
        LanNetworkFacts(
            revision = revision.incrementAndGet().toULong(),
            localNetworkPermissionGranted = snapshot.permissionGranted,
            candidates = snapshot.candidates,
        ),
    )
}

private fun publishDiscoverySnapshot(
    engine: LanCoordinatorEngine,
    discovery: LanShareDiscoveryCoordinator,
    revision: AtomicLong,
): List<LanDiscoveredPeer> {
    val peers =
        discovery.discoveredDevices.value.map { device ->
            LanDiscoveredPeer(
                deviceId = device.deviceId,
                displayName = device.name,
                host = device.host,
                port = device.port.toUInt(),
                protocolVersion = 2u,
            )
        }
    engine.updateLanDiscoverySnapshot(
        LanDiscoveryFacts(revision.incrementAndGet().toULong(), peers),
    )
    return engine.listLanDiscoveredPeers()
}

private fun Throwable.lanRuntimeDiagnostic(): String = message ?: this::class.simpleName.orEmpty()

private fun emptyRuntimeInbox() =
    LanRuntimeInbox(
        pairingChallenges = emptyList(),
        sessionChallenges = emptyList(),
        activeSessions = emptyList(),
        pendingBatches = emptyList(),
        batchRecoveries = emptyList(),
        committableItems = emptyList(),
        outgoingBatches = emptyList(),
    )
