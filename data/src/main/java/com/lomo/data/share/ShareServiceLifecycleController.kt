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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ShareServiceLifecycleController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val pairingConfig: SharePairingConfig,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val nsdService =
            NsdDiscoveryService(
                context = context,
                onDiscoveryStartFailed = ::handleDiscoveryStartFailed,
                onServiceRegistrationFailed = ::handleRegistrationFailed,
            )
        private val activeDiscoveryClient = LanShareActiveDiscoveryClient()
        private val server = LomoShareServer()
        private val serviceStateLock = Any()
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        private var networkCallbackRegistered = false
        private var activeNetworkSnapshot: LanShareActiveNetworkSnapshot? = null
        private val observedLanNetworks = LanShareObservedNetworkRegistry()

        val discoveredDevices: StateFlow<List<DiscoveredDevice>> = nsdService.discoveredDevices
        private val _lanShareStartupFailures =
            MutableSharedFlow<LanShareStartupFailure>(extraBufferCapacity = 1)
        val lanShareStartupFailures: SharedFlow<LanShareStartupFailure> =
            _lanShareStartupFailures.asSharedFlow()

        private var serverPort: Int = 0
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
                val shouldRefresh =
                    synchronized(serviceStateLock) {
                        servicesStarted && serverPort > 0
                    }
                if (!shouldRefresh) return@LanShareDebouncedAction

                runCatching {
                    val deviceName = pairingConfig.resolveDeviceName()
                    val targetNetwork = activeNetworkSnapshot?.network
                    server.updateDiscoveryDeviceName(deviceName)
                    nsdService.unregisterService()
                    nsdService.registerService(
                        port = serverPort,
                        deviceName = deviceName,
                        uuid = localUuid,
                        targetNetwork = targetNetwork,
                    )
                    Timber.tag(TAG).d("Refreshed NSD service name: $deviceName")
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
                val previousSnapshot =
                    synchronized(serviceStateLock) {
                        if (!servicesStarted && !discoveryStarted) {
                            return@LanShareDebouncedAction
                        }
                        activeNetworkSnapshot
                    }
                val currentSnapshot =
                    resolveLanShareActiveNetworkSnapshot(
                        connectivityManager = connectivityManager,
                        candidateNetworks = observedLanNetworks.snapshot(),
                    )
                if (currentSnapshot == previousSnapshot) return@LanShareDebouncedAction

                Timber.tag(TAG).d("Active LAN network changed; restarting LAN share services")
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
            server.onIncomingPrepare = onIncomingPrepare
            server.onSaveAttachment = onSaveAttachment
            server.onDeleteAttachment = onDeleteAttachment
            server.onSaveMemo = onSaveMemo
            server.getPairingKeyHex = { pairingConfig.getEffectivePairingKeyHex() }
            server.isE2eEnabled = { pairingConfig.isE2eEnabled() }
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
                    val networkSnapshot =
                        resolveLanShareActiveNetworkSnapshot(
                            connectivityManager = connectivityManager,
                            candidateNetworks = observedLanNetworks.snapshot(),
                        )
                            ?: error("No private LAN interface available for LAN share")
                    val deviceName = pairingConfig.resolveDeviceName()
                    serverPort =
                        server.start(
                            port = LAN_SHARE_DISCOVERY_PORT,
                            host = networkSnapshot.bindHost,
                            deviceName = deviceName,
                        )
                    val registered =
                        nsdService.registerService(
                            port = serverPort,
                            deviceName = deviceName,
                            uuid = localUuid,
                            targetNetwork = networkSnapshot.network,
                        )
                    if (!registered) {
                        _lanShareStartupFailures.tryEmit(LanShareStartupFailure.ServiceRegistrationFailed)
                        error("NSD service registration was rejected.")
                    }
                    activeNetworkSnapshot = networkSnapshot
                    ensureNetworkCallbackRegistered()
                    Timber.tag(TAG).d("Services started: server=$serverPort, device=$deviceName")
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    synchronized(serviceStateLock) {
                        servicesStarted = false
                    }
                    serverPort = 0
                    activeNetworkSnapshot = null
                    multicastLockLease.release(LanShareMulticastLockOwner.Service)
                    runCatching { nsdService.unregisterService() }
                        .onFailure { Timber.tag(TAG).e(it, "Failed to clean NSD registration after start failure") }
                    runCatching { server.stop() }
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
            }
            serverPort = 0
            activeNetworkSnapshot = null
            refreshRegistrationDebouncer.cancel()
            nsdService.stopDiscovery()
            nsdService.unregisterService()
            server.stop()
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
                    startActiveDiscoveryScan()
                    return@launch
                }
                multicastLockLease.acquire(LanShareMulticastLockOwner.Discovery)
                val targetNetwork =
                    activeNetworkSnapshot
                        ?: resolveLanShareActiveNetworkSnapshot(
                            connectivityManager = connectivityManager,
                            candidateNetworks = observedLanNetworks.snapshot(),
                        )
                val discoveryAccepted =
                    targetNetwork != null &&
                        nsdService.startDiscovery(
                            uuid = localUuid,
                            targetNetwork = targetNetwork.network,
                        )
                if (!discoveryAccepted) {
                    synchronized(serviceStateLock) {
                        discoveryStarted = false
                    }
                    multicastLockLease.release(LanShareMulticastLockOwner.Discovery)
                    _lanShareStartupFailures.tryEmit(LanShareStartupFailure.DiscoveryStartFailed)
                    unregisterNetworkCallbackIfIdle()
                } else {
                    if (activeNetworkSnapshot == null) {
                        activeNetworkSnapshot = targetNetwork
                    }
                    ensureNetworkCallbackRegistered()
                    startActiveDiscoveryScan(targetNetwork)
                }
            }
        }

        fun stopDiscovery() {
            synchronized(serviceStateLock) {
                discoveryStarted = false
            }
            nsdService.stopDiscovery()
            multicastLockLease.release(LanShareMulticastLockOwner.Discovery)
            unregisterNetworkCallbackIfIdle()
        }

        val acceptIncoming: () -> Unit = server::acceptIncoming

        fun rejectIncoming() {
            server.rejectIncoming()
        }

        fun refreshServiceRegistration() {
            refreshRegistrationDebouncer.trigger()
        }

        private fun handleDiscoveryStartFailed() {
            synchronized(serviceStateLock) {
                discoveryStarted = false
            }
            multicastLockLease.release(LanShareMulticastLockOwner.Discovery)
            _lanShareStartupFailures.tryEmit(LanShareStartupFailure.DiscoveryStartFailed)
            unregisterNetworkCallbackIfIdle()
        }

        private fun handleRegistrationFailed() {
            synchronized(serviceStateLock) {
                servicesStarted = false
            }
            serverPort = 0
            activeNetworkSnapshot = null
            multicastLockLease.release(LanShareMulticastLockOwner.Service)
            _lanShareStartupFailures.tryEmit(LanShareStartupFailure.ServiceRegistrationFailed)
            runCatching { server.stop() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.tag(TAG).e(error, "Failed to stop server after NSD registration failure")
                }
            unregisterNetworkCallbackIfIdle()
        }

        private fun startActiveDiscoveryScan(networkSnapshot: LanShareActiveNetworkSnapshot? = null) {
            scope.launch {
                val snapshot =
                    networkSnapshot
                        ?: synchronized(serviceStateLock) {
                            activeNetworkSnapshot
                        }
                        ?: resolveLanShareActiveNetworkSnapshot(
                            connectivityManager = connectivityManager,
                            candidateNetworks = observedLanNetworks.snapshot(),
                        )
                        ?: return@launch
                runCatching {
                    activeDiscoveryClient.scan(snapshot.bindHost)
                }.onSuccess { devices ->
                    val shouldMerge =
                        synchronized(serviceStateLock) {
                            discoveryStarted
                        }
                    if (shouldMerge) {
                        nsdService.mergeDiscoveredDevices(devices)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.tag(TAG).d(error, "LAN active discovery scan failed")
                }
            }
        }

        private companion object {
            private const val TAG = "ShareServiceLifecycle"
            private const val NSD_REFRESH_DEBOUNCE_MS = 500L
            private const val NETWORK_RESTART_DEBOUNCE_MS = 1_000L
        }
    }
