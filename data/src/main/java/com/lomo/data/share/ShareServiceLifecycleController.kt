package com.lomo.data.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanShareStartupFailure
import com.lomo.domain.model.SharePayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal interface LomoShareServerRuntime {
    suspend fun start(
        port: Int,
        host: String,
        deviceName: String,
    ): Int

    fun bindCallbacks(
        onIncomingPrepare: (SharePayload) -> Unit,
        onSaveAttachment: suspend (name: String, type: String, payloadFile: File) -> String?,
        onDeleteAttachment: suspend (savedPath: String, type: String) -> Unit,
        onSaveMemo: suspend (content: String, timestamp: Long, attachmentMappings: Map<String, String>) -> Unit,
        getPairingKeyHex: suspend () -> String?,
        isE2eEnabled: suspend () -> Boolean,
    )

    fun updateDiscoveryDeviceName(deviceName: String)

    fun stop()

    fun acceptIncoming()

    fun rejectIncoming()
}

private class RealLomoShareServerRuntime(
    private val server: LomoShareServer = LomoShareServer(),
) : LomoShareServerRuntime {
    override suspend fun start(
        port: Int,
        host: String,
        deviceName: String,
    ): Int =
        server.start(
            port = port,
            host = host,
            deviceName = deviceName,
        )

    override fun bindCallbacks(
        onIncomingPrepare: (SharePayload) -> Unit,
        onSaveAttachment: suspend (name: String, type: String, payloadFile: File) -> String?,
        onDeleteAttachment: suspend (savedPath: String, type: String) -> Unit,
        onSaveMemo: suspend (content: String, timestamp: Long, attachmentMappings: Map<String, String>) -> Unit,
        getPairingKeyHex: suspend () -> String?,
        isE2eEnabled: suspend () -> Boolean,
    ) {
        server.onIncomingPrepare = onIncomingPrepare
        server.onSaveAttachment = onSaveAttachment
        server.onDeleteAttachment = onDeleteAttachment
        server.onSaveMemo = onSaveMemo
        server.getPairingKeyHex = getPairingKeyHex
        server.isE2eEnabled = isE2eEnabled
    }

    override fun updateDiscoveryDeviceName(deviceName: String) {
        server.updateDiscoveryDeviceName(deviceName)
    }

    override fun stop() {
        server.stop()
    }

    override fun acceptIncoming() {
        server.acceptIncoming()
    }

    override fun rejectIncoming() {
        server.rejectIncoming()
    }
}

@OptIn(ExperimentalUuidApi::class)
class ShareServiceLifecycleController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val pairingConfig: SharePairingConfig,
    ) {
        internal constructor(
            context: Context,
            pairingConfig: SharePairingConfig,
            scope: CoroutineScope,
            discoveryCoordinator: LanShareDiscoveryCoordinator,
            activeDiscoveryScanner: LanShareActiveDiscoveryScanner,
            serverRuntime: LomoShareServerRuntime,
            resolveEligibleSnapshots: (ConnectivityManager?, Set<Network>) -> List<LanShareActiveNetworkSnapshot>,
        ) : this(context, pairingConfig) {
            this.scope = scope
            this.nsdService = discoveryCoordinator
            this.activeDiscoveryClient = activeDiscoveryScanner
            this.serverRuntime = serverRuntime
            this.resolveEligibleSnapshots = resolveEligibleSnapshots
        }

        private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var nsdService: LanShareDiscoveryCoordinator =
            NsdDiscoveryService(
                context = context,
                onDiscoveryStartFailed = ::handleDiscoveryStartFailed,
                onServiceRegistrationFailed = ::handleRegistrationFailed,
            )
        private var activeDiscoveryClient: LanShareActiveDiscoveryScanner = LanShareActiveDiscoveryClient()
        private var serverRuntime: LomoShareServerRuntime = RealLomoShareServerRuntime()
        private var resolveEligibleSnapshots:
            (ConnectivityManager?, Set<Network>) -> List<LanShareActiveNetworkSnapshot> =
            ::resolveLanShareEligibleNetworkSnapshots
        private val serviceStateLock = Any()
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        private var networkCallbackRegistered = false
        private var activeNetworkSnapshots: List<LanShareActiveNetworkSnapshot> = emptyList()
        private val observedLanNetworks = LanShareObservedNetworkRegistry()

        val discoveredDevices: StateFlow<List<DiscoveredDevice>>
            get() = nsdService.discoveredDevices
        private val _lanShareStartupFailures =
            MutableSharedFlow<LanShareStartupFailure>(extraBufferCapacity = 1)
        val lanShareStartupFailures: SharedFlow<LanShareStartupFailure> =
            _lanShareStartupFailures.asSharedFlow()

        private var serverPort: Int = 0
        private var activeDiscoveryJob: Job? = null
        private val localUuid = Uuid.random().toString()

        @Volatile
        private var servicesStarted = false

        @Volatile
        private var discoveryStarted = false

        private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        private val multicastLockManager = LanShareMulticastLockManager(wifiManager)
        private val multicastLockLease =
            LanShareMulticastLockLease(
                acquireLock = multicastLockManager::acquire,
                releaseLock = multicastLockManager::releaseIfHeld,
            )
        private val refreshRegistrationDebouncer =
            LanShareDebouncedAction(
                scope = scope,
                dispatcher = Dispatchers.IO,
                delayMs = NSD_REFRESH_DEBOUNCE_MS,
            ) {
                val (shouldRefresh, snapshotsForRefresh) =
                    synchronized(serviceStateLock) {
                        (servicesStarted && serverPort > 0) to activeNetworkSnapshots
                    }
                if (!shouldRefresh) return@LanShareDebouncedAction

                runCatching {
                    val deviceName = pairingConfig.resolveDeviceName()
                    serverRuntime.updateDiscoveryDeviceName(deviceName)
                    snapshotsForRefresh.forEach { snapshot ->
                        nsdService.registerService(
                            networkKey = snapshot.networkKey,
                            port = serverPort,
                            deviceName = deviceName,
                            uuid = localUuid,
                            targetNetwork = snapshot.network,
                        )
                    }
                    Timber
                        .tag(TAG)
                        .d(
                            "Refreshed NSD service name on %d snapshots: %s",
                            snapshotsForRefresh.size,
                            deviceName,
                        )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.tag(TAG).e(error, "Failed to refresh NSD registration")
                }
            }
        private val networkRestartDebouncer =
            LanShareDebouncedAction(
                scope = scope,
                dispatcher = Dispatchers.IO,
                delayMs = NETWORK_RESTART_DEBOUNCE_MS,
            ) {
                val previousSnapshots =
                    synchronized(serviceStateLock) {
                        if (!servicesStarted && !discoveryStarted) {
                            return@LanShareDebouncedAction
                        }
                        activeNetworkSnapshots.toSet()
                    }
                val currentSnapshots =
                    resolveEligibleSnapshots(
                        connectivityManager,
                        observedLanNetworks.snapshot(),
                    ).toSet()
                if (currentSnapshots == previousSnapshots) return@LanShareDebouncedAction

                Timber.tag(TAG).d("Active LAN networks changed; restarting LAN share services")
                stopServices()
                if (pairingConfig.isLanShareEnabled()) {
                    startServices()
                    startDiscovery()
                }
            }
        private val ensureNetworkCallbackRegistered: () -> Unit = {
            val manager = connectivityManager
            if (!networkCallbackRegistered && manager != null) {
                runCatching {
                    manager.registerDefaultNetworkCallback(networkCallback)
                    networkCallbackRegistered = true
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.tag(TAG).e(error, "Failed to register LAN share network callback")
                }
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                !localNetworkCallbackRegistered &&
                manager != null
            ) {
                runCatching {
                    manager.registerNetworkCallback(
                        NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
                            .build(),
                        localNetworkCallback,
                    )
                    localNetworkCallbackRegistered = true
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.tag(TAG).e(error, "Failed to register LAN share local-network callback")
                }
            }
        }
        private val unregisterNetworkCallbackIfIdle: () -> Unit = {
            val shouldUnregister =
                synchronized(serviceStateLock) {
                    !servicesStarted && !discoveryStarted
                }
            val manager = connectivityManager
            if (shouldUnregister && manager != null && networkCallbackRegistered) {
                runCatching {
                    manager.unregisterNetworkCallback(networkCallback)
                    networkCallbackRegistered = false
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.tag(TAG).e(error, "Failed to unregister LAN share network callback")
                }
            }
            if (shouldUnregister && manager != null && localNetworkCallbackRegistered) {
                runCatching {
                    manager.unregisterNetworkCallback(localNetworkCallback)
                    localNetworkCallbackRegistered = false
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.tag(TAG).e(error, "Failed to unregister LAN share local-network callback")
                }
            }
        }
        private val networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    observedLanNetworks.remember(network)
                    networkRestartDebouncer.trigger()
                }

                override fun onLost(network: android.net.Network) {
                    observedLanNetworks.forget(network)
                    networkRestartDebouncer.trigger()
                }

                override fun onLinkPropertiesChanged(
                    network: android.net.Network,
                    linkProperties: android.net.LinkProperties,
                ) {
                    networkRestartDebouncer.trigger()
                }
            }
        private val localNetworkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    observedLanNetworks.remember(network)
                    networkRestartDebouncer.trigger()
                }

                override fun onLost(network: android.net.Network) {
                    observedLanNetworks.forget(network)
                    networkRestartDebouncer.trigger()
                }

                override fun onLinkPropertiesChanged(
                    network: android.net.Network,
                    linkProperties: android.net.LinkProperties,
                ) {
                    networkRestartDebouncer.trigger()
                }
            }
        private var localNetworkCallbackRegistered = false

        fun bindServerCallbacks(
            onIncomingPrepare: (SharePayload) -> Unit,
            onSaveAttachment: suspend (name: String, type: String, payloadFile: File) -> String?,
            onDeleteAttachment: suspend (savedPath: String, type: String) -> Unit,
            onSaveMemo: suspend (content: String, timestamp: Long, attachmentMappings: Map<String, String>) -> Unit,
        ) {
            serverRuntime.bindCallbacks(
                onIncomingPrepare = onIncomingPrepare,
                onSaveAttachment = onSaveAttachment,
                onDeleteAttachment = onDeleteAttachment,
                onSaveMemo = onSaveMemo,
                getPairingKeyHex = { pairingConfig.getEffectivePairingKeyHex() },
                isE2eEnabled = { pairingConfig.isE2eEnabled() },
            )
        }

        fun startServices() {
            scope.launch {
                if (!pairingConfig.isLanShareEnabled()) {
                    Timber.tag(TAG).d("Skip service start because LAN share is disabled")
                    return@launch
                }
                val shouldStart =
                    synchronized(serviceStateLock) {
                        if (servicesStarted) {
                            false
                        } else {
                            servicesStarted = true
                            true
                        }
                    }
                if (!shouldStart) return@launch

                multicastLockLease.acquire(LanShareMulticastLockOwner.Service)
                runCatching {
                    val eligibleSnapshots =
                        resolveEligibleSnapshots(
                            connectivityManager,
                            observedLanNetworks.snapshot(),
                        ).takeIf { it.isNotEmpty() }
                            ?: error("No private LAN interface available for LAN share")
                    val deviceName = pairingConfig.resolveDeviceName()
                    serverPort =
                        serverRuntime.start(
                            port = LAN_SHARE_DISCOVERY_PORT,
                            host = LAN_SHARE_SERVER_BIND_HOST,
                            deviceName = deviceName,
                        )
                    var anyRegistered = false
                    eligibleSnapshots.forEach { snapshot ->
                        val registered =
                            nsdService.registerService(
                                networkKey = snapshot.networkKey,
                                port = serverPort,
                                deviceName = deviceName,
                                uuid = localUuid,
                                targetNetwork = snapshot.network,
                            )
                        if (registered) {
                            anyRegistered = true
                        } else {
                            Timber
                                .tag(TAG)
                                .d(
                                    "NSD service registration rejected; bindHost=%s, networkKey=%s",
                                    snapshot.bindHost,
                                    snapshot.networkKey,
                                )
                        }
                    }
                    if (!anyRegistered) {
                        _lanShareStartupFailures.tryEmit(LanShareStartupFailure.ServiceRegistrationFailed)
                    }
                    synchronized(serviceStateLock) {
                        activeNetworkSnapshots = eligibleSnapshots
                    }
                    ensureNetworkCallbackRegistered()
                    Timber
                        .tag(TAG)
                        .d(
                            "Services started: server=%d, device=%s, snapshots=%s",
                            serverPort,
                            deviceName,
                            eligibleSnapshots.joinToString { "${it.networkKey}@${it.bindHost}" },
                        )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    synchronized(serviceStateLock) {
                        servicesStarted = false
                        activeNetworkSnapshots = emptyList()
                    }
                    serverPort = 0
                    multicastLockLease.release(LanShareMulticastLockOwner.Service)
                    runCatching { nsdService.unregisterAll() }
                        .onFailure { Timber.tag(TAG).e(it, "Failed to clean NSD registration after start failure") }
                    runCatching { serverRuntime.stop() }
                        .onFailure { Timber.tag(TAG).e(it, "Failed to stop server after start failure") }
                    unregisterNetworkCallbackIfIdle()
                    Timber.tag(TAG).e(error, "Failed to start services")
                }
            }
        }

        fun stopServices() {
            synchronized(serviceStateLock) {
                servicesStarted = false
                discoveryStarted = false
                activeNetworkSnapshots = emptyList()
            }
            serverPort = 0
            activeDiscoveryJob?.cancel()
            activeDiscoveryJob = null
            refreshRegistrationDebouncer.cancel()
            nsdService.stopAllDiscovery()
            nsdService.unregisterAll()
            serverRuntime.stop()
            multicastLockLease.release(LanShareMulticastLockOwner.Discovery)
            multicastLockLease.release(LanShareMulticastLockOwner.Service)
            unregisterNetworkCallbackIfIdle()
            Timber.tag(TAG).d("Services stopped")
        }

        fun startDiscovery() {
            scope.launch {
                if (!pairingConfig.isLanShareEnabled()) {
                    Timber.tag(TAG).d("Skip discovery start because LAN share is disabled")
                    return@launch
                }
                val alreadyStarted =
                    synchronized(serviceStateLock) {
                        if (discoveryStarted) {
                            true
                        } else {
                            discoveryStarted = true
                            false
                        }
                    }
                if (alreadyStarted) {
                    restartActiveDiscoveryScans()
                    return@launch
                }
                multicastLockLease.acquire(LanShareMulticastLockOwner.Discovery)
                val targetSnapshots =
                    synchronized(serviceStateLock) { activeNetworkSnapshots }
                        .ifEmpty {
                            resolveEligibleSnapshots(
                                connectivityManager,
                                observedLanNetworks.snapshot(),
                            )
                        }
                if (targetSnapshots.isEmpty()) {
                    synchronized(serviceStateLock) {
                        discoveryStarted = false
                    }
                    multicastLockLease.release(LanShareMulticastLockOwner.Discovery)
                    _lanShareStartupFailures.tryEmit(LanShareStartupFailure.DiscoveryStartFailed)
                    unregisterNetworkCallbackIfIdle()
                    return@launch
                }
                var anyDiscoveryAccepted = false
                targetSnapshots.forEach { snapshot ->
                    val accepted =
                        nsdService.startDiscovery(
                            networkKey = snapshot.networkKey,
                            uuid = localUuid,
                            targetNetwork = snapshot.network,
                        )
                    if (accepted) anyDiscoveryAccepted = true
                }
                if (!anyDiscoveryAccepted) {
                    _lanShareStartupFailures.tryEmit(LanShareStartupFailure.DiscoveryStartFailed)
                    Timber.tag(TAG).d("NSD discovery was rejected on every snapshot; continuing active LAN scan")
                }
                synchronized(serviceStateLock) {
                    if (activeNetworkSnapshots.isEmpty()) {
                        activeNetworkSnapshots = targetSnapshots
                    }
                }
                ensureNetworkCallbackRegistered()
                restartActiveDiscoveryScans(targetSnapshots)
            }
        }

        fun stopDiscovery() {
            synchronized(serviceStateLock) {
                discoveryStarted = false
            }
            activeDiscoveryJob?.cancel()
            activeDiscoveryJob = null
            nsdService.stopAllDiscovery()
            multicastLockLease.release(LanShareMulticastLockOwner.Discovery)
            unregisterNetworkCallbackIfIdle()
        }

        val acceptIncoming: () -> Unit
            get() = serverRuntime::acceptIncoming

        fun rejectIncoming() {
            serverRuntime.rejectIncoming()
        }

        fun refreshServiceRegistration() {
            refreshRegistrationDebouncer.trigger()
        }

        private fun handleDiscoveryStartFailed() {
            val snapshots =
                synchronized(serviceStateLock) {
                    activeNetworkSnapshots
                }
            synchronized(serviceStateLock) {
                discoveryStarted = snapshots.isNotEmpty()
            }
            _lanShareStartupFailures.tryEmit(LanShareStartupFailure.DiscoveryStartFailed)
            if (snapshots.isEmpty()) {
                multicastLockLease.release(LanShareMulticastLockOwner.Discovery)
                unregisterNetworkCallbackIfIdle()
            } else {
                restartActiveDiscoveryScans(snapshots)
            }
        }

        private fun handleRegistrationFailed() {
            _lanShareStartupFailures.tryEmit(LanShareStartupFailure.ServiceRegistrationFailed)
            Timber.tag(TAG).d("NSD registration failed; keeping active share server for direct LAN probes")
        }

        private fun restartActiveDiscoveryScans(snapshots: List<LanShareActiveNetworkSnapshot> = emptyList()) {
            activeDiscoveryJob?.cancel()
            activeDiscoveryJob =
                scope.launch {
                    while (
                        isActive &&
                        synchronized(serviceStateLock) {
                            discoveryStarted
                        }
                    ) {
                        runActiveDiscoveryScan(snapshots)
                        delay(ACTIVE_DISCOVERY_RETRY_DELAY_MS)
                    }
                }
        }

        private suspend fun runActiveDiscoveryScan(seedSnapshots: List<LanShareActiveNetworkSnapshot>) {
            val snapshots =
                seedSnapshots
                    .ifEmpty {
                        synchronized(serviceStateLock) { activeNetworkSnapshots }
                    }
                    .ifEmpty {
                        resolveEligibleSnapshots(
                            connectivityManager,
                            observedLanNetworks.snapshot(),
                        )
                    }
            if (snapshots.isEmpty()) return

            val merged =
                runCatching {
                    coroutineScope {
                        snapshots
                            .map { snapshot ->
                                async {
                                    runCatching { activeDiscoveryClient.scan(snapshot) }
                                        .onFailure { error ->
                                            if (error is CancellationException) throw error
                                            Timber
                                                .tag(TAG)
                                                .d(
                                                    error,
                                                    "LAN active discovery scan failed for %s",
                                                    snapshot.networkKey,
                                                )
                                        }.getOrDefault(emptyList())
                                }
                            }.awaitAll()
                            .flatten()
                            .distinctBy { device -> "${device.host}:${device.port}" }
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.tag(TAG).d(error, "LAN active discovery scan aggregation failed")
                }.getOrDefault(emptyList())

            val shouldMerge =
                synchronized(serviceStateLock) {
                    discoveryStarted
                }
            if (shouldMerge && merged.isNotEmpty()) {
                nsdService.mergeDiscoveredDevices(merged)
            }
            if (merged.isEmpty()) {
                Timber
                    .tag(TAG)
                    .d(
                        "LAN active discovery scan returned no peers across %d snapshots",
                        snapshots.size,
                    )
            }
        }

        private companion object {
            private const val TAG = "ShareServiceLifecycle"
            private const val NSD_REFRESH_DEBOUNCE_MS = 500L
            private const val NETWORK_RESTART_DEBOUNCE_MS = 1_000L
            private const val ACTIVE_DISCOVERY_RETRY_DELAY_MS = 1_000L
            private const val LAN_SHARE_SERVER_BIND_HOST = "0.0.0.0"
        }
    }
