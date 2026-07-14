package com.lomo.data.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanShareActiveProbeDiagnostics
import com.lomo.domain.model.LanShareRuntimeState
import com.lomo.domain.model.LanShareStartupFailure
import com.lomo.domain.model.SharePayload

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File

import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.cancellation.CancellationException

internal interface LomoShareServerRuntime {
    suspend fun start(
        port: Int,
        host: String,
        deviceName: String,
        deviceUuid: String,
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
        deviceUuid: String,
    ): Int =
        server.start(
            deviceUuid = deviceUuid,
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

private fun createLanShareDebouncedAction(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    delayMs: Long,
    action: suspend () -> Unit,
): LanShareDebouncedAction =
    LanShareDebouncedAction(
        scope = scope,
        dispatcher = dispatcher,
        delayMs = delayMs,
        action = action,
    )

class ShareServiceLifecycleController(
    private val context: Context,
    private val pairingConfig: SharePairingConfig,
    private val deviceIdentityProvider: LanShareDeviceIdentityProvider,
) {
        internal constructor(
            context: Context,
            pairingConfig: SharePairingConfig,
            deviceIdentityProvider: LanShareDeviceIdentityProvider,
            scope: CoroutineScope,
            discoveryCoordinator: LanShareDiscoveryCoordinator,
            activeDiscoveryScanner: LanShareActiveDiscoveryScanner,
            serverRuntime: LomoShareServerRuntime,
            resolveEligibleSnapshots: (ConnectivityManager?, Set<Network>) -> List<LanShareActiveNetworkSnapshot>,
            networkPermissionGateway: LanShareNetworkPermissionGateway =
                AndroidLanShareNetworkPermissionGateway(context),
        ) : this(context, pairingConfig, deviceIdentityProvider) {
            this.scope = scope
            this.nsdService = discoveryCoordinator
            this.activeDiscoveryClient = activeDiscoveryScanner
            this.serverRuntime = serverRuntime
            this.resolveEligibleSnapshots = resolveEligibleSnapshots
            this.networkPermissionGateway = networkPermissionGateway
            this.activeDiscoveryLoop = createActiveDiscoveryLoop()
            val dispatcher = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.IO
            this.refreshRegistrationDebouncer =
                createLanShareDebouncedAction(
                    scope = scope,
                    dispatcher = dispatcher,
                    delayMs = NSD_REFRESH_DEBOUNCE_MS,
                    action = refreshRegistrationAction,
                )
            this.networkRestartDebouncer =
                createLanShareDebouncedAction(
                    scope = scope,
                    dispatcher = dispatcher,
                    delayMs = NETWORK_RESTART_DEBOUNCE_MS,
                    action = networkRestartAction,
                )
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
        private var networkPermissionGateway: LanShareNetworkPermissionGateway =
            AndroidLanShareNetworkPermissionGateway(context)
        private val runtimeReconcileMutex = Mutex()
        private val serviceStateLock = Any()
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        private var networkCallbackRegistered = false
        private var activeServiceSnapshots: List<LanShareActiveNetworkSnapshot> = emptyList()
        private var activeDiscoverySnapshots: List<LanShareActiveNetworkSnapshot> = emptyList()
        private val observedLanNetworks = LanShareObservedNetworkRegistry()
        private val runtimeSnapshot: () -> LanShareDiagnosticsRuntimeSnapshot = {
            synchronized(serviceStateLock) {
                LanShareDiagnosticsRuntimeSnapshot(
                    servicesDesired = servicesDesired,
                    discoveryDesired = discoveryDesired,
                    serviceSnapshots = activeServiceSnapshots,
                    discoverySnapshots = activeDiscoverySnapshots,
                    serverPort = serverPort,
                )
            }
        }

        val discoveredDevices: StateFlow<List<DiscoveredDevice>>
            get() = nsdService.discoveredDevices
        private val diagnosticsPublisher =
            LanShareDiscoveryDiagnosticsPublisher(runtimeSnapshot = runtimeSnapshot)
        val lanShareRuntimeState: StateFlow<LanShareRuntimeState> = diagnosticsPublisher.runtimeState
        val lanShareDiscoveryDiagnostics = diagnosticsPublisher.diagnostics
        private var activeDiscoveryLoop = createActiveDiscoveryLoop()
        private val _lanShareStartupFailures =
            MutableSharedFlow<LanShareStartupFailure>(extraBufferCapacity = 1)
        val lanShareStartupFailures: SharedFlow<LanShareStartupFailure> =
            _lanShareStartupFailures.asSharedFlow()

        private var serverPort: Int = 0
        @Volatile
        private var resolvedLocalUuid: String? = null

        @Volatile
        private var servicesDesired = false

        @Volatile
        private var discoveryDesired = false

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
        private val refreshRegistrationAction: suspend () -> Unit = {
            val (shouldRefresh, snapshotsForRefresh) =
                synchronized(serviceStateLock) {
                    (servicesStarted && serverPort > 0) to activeServiceSnapshots
                }
            if (shouldRefresh) {
                runCatching {
                    val deviceName = pairingConfig.resolveDeviceName()
                    val deviceUuid = resolveLocalUuid()
                    serverRuntime.updateDiscoveryDeviceName(deviceName)
                    nsdService.registerService(
                        port = serverPort,
                        deviceName = deviceName,
                        uuid = deviceUuid,
                    )
                    Timber
                        .tag(TAG)
                        .d(
                            "Refreshed global NSD service name across %d active snapshots: %s",
                            snapshotsForRefresh.size,
                            deviceName,
                        )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.tag(TAG).e(error, "Failed to refresh NSD registration")
                }
            }
        }
        private val networkRestartAction: suspend () -> Unit = {
            Timber.tag(TAG).d("Active LAN networks changed; restarting LAN share services")
            reconcileDesiredRuntime()
        }
        private var refreshRegistrationDebouncer =
            createLanShareDebouncedAction(
                scope = scope,
                dispatcher = Dispatchers.IO,
                delayMs = NSD_REFRESH_DEBOUNCE_MS,
                action = refreshRegistrationAction,
            )
        private var networkRestartDebouncer =
            createLanShareDebouncedAction(
                scope = scope,
                dispatcher = Dispatchers.IO,
                delayMs = NETWORK_RESTART_DEBOUNCE_MS,
                action = networkRestartAction,
            )
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
                    !servicesDesired && !discoveryDesired && !servicesStarted && !discoveryStarted
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
                    diagnosticsPublisher.publishRuntimeState(LanShareRuntimeState.Stopped)
                    return@launch
                }
                synchronized(serviceStateLock) {
                    servicesDesired = true
                }
                ensureNetworkCallbackRegistered()
                reconcileDesiredRuntime()
            }
        }

        fun stopServices() {
            synchronized(serviceStateLock) {
                servicesDesired = false
                discoveryDesired = false
                servicesStarted = false
                discoveryStarted = false
                activeServiceSnapshots = emptyList()
                activeDiscoverySnapshots = emptyList()
            }
            serverPort = 0
            activeDiscoveryLoop.stop()
            refreshRegistrationDebouncer.cancel()
            nsdService.stopDiscovery()
            nsdService.unregisterService()
            serverRuntime.stop()
            multicastLockLease.release(LanShareMulticastLockOwner.Discovery)
            multicastLockLease.release(LanShareMulticastLockOwner.Service)
            diagnosticsPublisher.publishRuntimeState(
                state = LanShareRuntimeState.Stopped,
                activeProbe = LanShareActiveProbeDiagnostics(),
                degradedReason = null,
            )
            unregisterNetworkCallbackIfIdle()
            Timber.tag(TAG).d("Services stopped")
        }

        fun startDiscovery() {
            scope.launch {
                if (!pairingConfig.isLanShareEnabled()) {
                    Timber.tag(TAG).d("Skip discovery start because LAN share is disabled")
                    diagnosticsPublisher.publishRuntimeState(LanShareRuntimeState.Stopped)
                    return@launch
                }
                synchronized(serviceStateLock) {
                    discoveryDesired = true
                }
                ensureNetworkCallbackRegistered()
                reconcileDesiredRuntime()
            }
        }

        fun stopDiscovery() {
            synchronized(serviceStateLock) {
                discoveryDesired = false
                discoveryStarted = false
            }
            activeDiscoveryLoop.stop()
            nsdService.stopDiscovery()
            multicastLockLease.release(LanShareMulticastLockOwner.Discovery)
            publishRuntimeStateAfterStop()
            unregisterNetworkCallbackIfIdle()
        }

        fun refreshNetworkPermissionState() {
            scope.launch {
                reconcileDesiredRuntime()
            }
        }

        val acceptIncoming: () -> Unit = {
            serverRuntime.acceptIncoming()
        }

        val rejectIncoming: () -> Unit = {
            serverRuntime.rejectIncoming()
        }

        val refreshServiceRegistration: () -> Unit = {
            refreshRegistrationDebouncer.trigger()
        }

        private fun createActiveDiscoveryLoop(): LanShareActiveDiscoveryLoop =
            LanShareActiveDiscoveryLoop(
                scope = scope,
                activeDiscoveryClient = activeDiscoveryClient,
                discoveryCoordinator = nsdService,
                isDiscoveryStarted = { synchronized(serviceStateLock) { discoveryStarted } },
                activeSnapshots = { synchronized(serviceStateLock) { activeDiscoverySnapshots } },
                resolveEligibleSnapshots = {
                    resolveEligibleSnapshots(
                        connectivityManager,
                        observedLanNetworks.snapshot(),
                    )
                },
                publishDiagnostics = diagnosticsPublisher::publishActiveDiscoveryDiagnostics,
            )

        private suspend fun reconcileDesiredRuntime() {
            runtimeReconcileMutex.withLock {
                val (wantsServices, wantsDiscovery) =
                    synchronized(serviceStateLock) {
                        servicesDesired to discoveryDesired
                    }
                if (!wantsServices && !wantsDiscovery) {
                    diagnosticsPublisher.publishRuntimeState(LanShareRuntimeState.Stopped)
                    unregisterNetworkCallbackIfIdle()
                    return
                }
                if (!pairingConfig.isLanShareEnabled()) {
                    synchronized(serviceStateLock) {
                        servicesDesired = false
                        discoveryDesired = false
                    }
                    stopActualRuntimeForWaiting()
                    diagnosticsPublisher.publishRuntimeState(
                        state = LanShareRuntimeState.Stopped,
                        activeProbe = LanShareActiveProbeDiagnostics(),
                        degradedReason = null,
                    )
                    unregisterNetworkCallbackIfIdle()
                    return
                }
                ensureNetworkCallbackRegistered()
                if (!networkPermissionGateway.hasRequiredPermissions()) {
                    stopActualRuntimeForWaiting()
                    diagnosticsPublisher.publishRuntimeState(
                        state = LanShareRuntimeState.PermissionBlocked,
                        activeProbe = LanShareActiveProbeDiagnostics(),
                        degradedReason = null,
                    )
                    Timber.tag(TAG).d("LAN share waiting for local-network permissions")
                    return
                }

                val eligibleSnapshots =
                    resolveEligibleSnapshots(
                        connectivityManager,
                        observedLanNetworks.snapshot(),
                    )
                if (eligibleSnapshots.isEmpty()) {
                    stopActualRuntimeForWaiting()
                    diagnosticsPublisher.publishRuntimeState(
                        state = LanShareRuntimeState.WaitingForTopology,
                        activeProbe = LanShareActiveProbeDiagnostics(),
                        degradedReason = null,
                    )
                    Timber.tag(TAG).d("LAN share waiting for eligible LAN topology")
                    return
                }

                if (wantsServices) {
                    ensureServicesStarted(eligibleSnapshots)
                } else {
                    stopActualServices()
                }
                if (wantsDiscovery) {
                    ensureDiscoveryStarted(eligibleSnapshots)
                } else {
                    stopActualDiscovery()
                }
                synchronized(serviceStateLock) {
                    if (servicesStarted || discoveryStarted) {
                        diagnosticsPublisher.publishRuntimeState(LanShareRuntimeState.Running)
                    }
                }
            }
        }

        private val ensureServicesStarted: suspend (
            List<LanShareActiveNetworkSnapshot>,
        ) -> Unit = { eligibleSnapshots ->
            val alreadyStarted =
                synchronized(serviceStateLock) {
                    servicesStarted && serverPort > 0
                }
            if (alreadyStarted) {
                synchronized(serviceStateLock) {
                    activeServiceSnapshots = eligibleSnapshots
                }
            } else {
                var acquiredServiceLease = false
                runCatching {
                    val deviceName = pairingConfig.resolveDeviceName()
                    val deviceUuid = resolveLocalUuid()
                    multicastLockLease.acquire(LanShareMulticastLockOwner.Service)
                    acquiredServiceLease = true
                    serverPort =
                        serverRuntime.start(
                            port = LAN_SHARE_DISCOVERY_PORT,
                            host = LAN_SHARE_SERVER_BIND_HOST,
                            deviceName = deviceName,
                            deviceUuid = deviceUuid,
                        )
                    val registered =
                        nsdService.registerService(
                            port = serverPort,
                            deviceName = deviceName,
                            uuid = deviceUuid,
                        )
                    if (!registered) {
                        _lanShareStartupFailures.tryEmit(LanShareStartupFailure.ServiceRegistrationFailed)
                        Timber
                            .tag(TAG)
                            .d("Global NSD service registration rejected; direct probe server remains active")
                    }
                    synchronized(serviceStateLock) {
                        servicesStarted = true
                        activeServiceSnapshots = eligibleSnapshots
                    }
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
                    if (acquiredServiceLease && serverPort == 0) {
                        multicastLockLease.release(LanShareMulticastLockOwner.Service)
                    }
                    stopActualServices()
                    Timber.tag(TAG).e(error, "Failed to start services")
                }
            }
        }

        private val ensureDiscoveryStarted: suspend (
            List<LanShareActiveNetworkSnapshot>,
        ) -> Unit = { eligibleSnapshots ->
            val deviceUuid = resolveLocalUuid()
            val wasStarted =
                synchronized(serviceStateLock) {
                    discoveryStarted
                }
            if (!wasStarted) {
                multicastLockLease.acquire(LanShareMulticastLockOwner.Discovery)
                val accepted = nsdService.startDiscovery(deviceUuid)
                if (!accepted) {
                    _lanShareStartupFailures.tryEmit(LanShareStartupFailure.DiscoveryStartFailed)
                    Timber.tag(TAG).d("Global NSD discovery was rejected; continuing active LAN scan")
                }
                synchronized(serviceStateLock) {
                    discoveryStarted = true
                }
            }
            val routesChanged =
                synchronized(serviceStateLock) {
                    val changed = activeDiscoverySnapshots != eligibleSnapshots
                    activeDiscoverySnapshots = eligibleSnapshots
                    changed
                }
            if (routesChanged || !activeDiscoveryLoop.isRunning) {
                activeDiscoveryLoop.restart(eligibleSnapshots, deviceUuid)
            }
        }

        private val stopActualRuntimeForWaiting: () -> Unit = {
            stopActualDiscovery()
            stopActualServices()
            synchronized(serviceStateLock) {
                if (!servicesStarted && !discoveryStarted) {
                    activeServiceSnapshots = emptyList()
                    activeDiscoverySnapshots = emptyList()
                }
            }
        }

        private val publishRuntimeStateAfterStop: () -> Unit = {
            val state =
                synchronized(serviceStateLock) {
                    if (servicesStarted || discoveryStarted) {
                        LanShareRuntimeState.Running
                    } else {
                        LanShareRuntimeState.Stopped
                    }
                }
            diagnosticsPublisher.publishRuntimeState(
                state = state,
                activeProbe = LanShareActiveProbeDiagnostics(),
                degradedReason = null,
            )
        }

        private val stopActualServices: () -> Unit = {
            val shouldStop =
                synchronized(serviceStateLock) {
                    val wasStarted = servicesStarted || serverPort > 0
                    servicesStarted = false
                    activeServiceSnapshots = emptyList()
                    wasStarted
                }
            if (shouldStop) {
                serverPort = 0
                refreshRegistrationDebouncer.cancel()
                runCatching { nsdService.unregisterService() }
                    .onFailure { Timber.tag(TAG).e(it, "Failed to unregister LAN share services") }
                runCatching { serverRuntime.stop() }
                    .onFailure { Timber.tag(TAG).e(it, "Failed to stop LAN share server") }
                multicastLockLease.release(LanShareMulticastLockOwner.Service)
            }
        }

        private val stopActualDiscovery: () -> Unit = {
            val shouldStop =
                synchronized(serviceStateLock) {
                    val wasStarted = discoveryStarted || activeDiscoveryLoop.hasJob
                    discoveryStarted = false
                    activeDiscoverySnapshots = emptyList()
                    wasStarted
                }
            if (shouldStop) {
                activeDiscoveryLoop.stop()
                runCatching { nsdService.stopDiscovery() }
                    .onFailure { Timber.tag(TAG).e(it, "Failed to stop LAN share discovery") }
                multicastLockLease.release(LanShareMulticastLockOwner.Discovery)
            }
        }

        private fun handleDiscoveryStartFailed() {
            val snapshots =
                synchronized(serviceStateLock) {
                    activeDiscoverySnapshots
                }
            synchronized(serviceStateLock) {
                discoveryStarted = snapshots.isNotEmpty()
            }
            _lanShareStartupFailures.tryEmit(LanShareStartupFailure.DiscoveryStartFailed)
            if (snapshots.isEmpty()) {
                multicastLockLease.release(LanShareMulticastLockOwner.Discovery)
                unregisterNetworkCallbackIfIdle()
            } else {
                scope.launch {
                    activeDiscoveryLoop.startIfIdle(snapshots, resolveLocalUuid())
                }
            }
        }

        private suspend fun resolveLocalUuid(): String {
            resolvedLocalUuid?.let { return it }
            return deviceIdentityProvider.resolveUuid().also { uuid ->
                resolvedLocalUuid = uuid
            }
        }

        private fun handleRegistrationFailed() {
            _lanShareStartupFailures.tryEmit(LanShareStartupFailure.ServiceRegistrationFailed)
            Timber.tag(TAG).d("NSD registration failed; keeping active share server for direct LAN probes")
        }

        private companion object {
            private const val TAG = "ShareServiceLifecycle"
            private const val NSD_REFRESH_DEBOUNCE_MS = 500L
            private const val NETWORK_RESTART_DEBOUNCE_MS = 1_000L
            private const val LAN_SHARE_SERVER_BIND_HOST = "0.0.0.0"
        }
    }
