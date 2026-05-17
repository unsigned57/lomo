package com.lomo.data.share

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import com.lomo.domain.model.DiscoveredDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages NSD (Network Service Discovery) for discovering and advertising
 * Lomo instances on the local network.
 */
interface LanShareDiscoveryCoordinator {
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>

    fun registerService(
        networkKey: String,
        port: Int,
        deviceName: String,
        uuid: String,
        targetNetwork: Network? = null,
    ): Boolean

    fun unregisterService(networkKey: String)

    fun unregisterAll()

    fun startDiscovery(
        networkKey: String,
        uuid: String,
        targetNetwork: Network? = null,
    ): Boolean

    fun stopDiscovery(networkKey: String)

    fun stopAllDiscovery()

    fun mergeDiscoveredDevices(devices: List<DiscoveredDevice>)
}

class NsdDiscoveryService(
    private val context: Context,
    private val onDiscoveryStartFailed: () -> Unit = {},
    private val onServiceRegistrationFailed: () -> Unit = {},
) : LanShareDiscoveryCoordinator {
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val registrationListeners = ConcurrentHashMap<String, NsdManager.RegistrationListener>()
    private val discoveryListeners = ConcurrentHashMap<String, NsdManager.DiscoveryListener>()
    private val registeredServiceNames = ConcurrentHashMap<String, String>()
    private val serviceInfoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
    private var localUuid: String? = null

    // --- Service Registration ---

    override fun registerService(
        networkKey: String,
        port: Int,
        deviceName: String,
        uuid: String,
        targetNetwork: Network?,
    ): Boolean {
        unregisterService(networkKey)

        val serviceInfo =
            NsdServiceInfo().apply {
                serviceName = "$SERVICE_NAME_PREFIX$deviceName"
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("uuid", uuid)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && targetNetwork != null) {
                    setNetwork(targetNetwork)
                }
            }

        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    registeredServiceNames[networkKey] = info.serviceName
                    Timber.tag(TAG).d("Service registered on %s: %s", networkKey, info.serviceName)
                }

                override fun onRegistrationFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Timber.tag(TAG).e("Registration failed on %s: %d", networkKey, errorCode)
                    registrationListeners.remove(networkKey, this)
                    registeredServiceNames.remove(networkKey)
                    onServiceRegistrationFailed()
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Timber.tag(TAG).d("Service unregistered on %s: %s", networkKey, info.serviceName)
                }

                override fun onUnregistrationFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Timber.tag(TAG).e("Unregistration failed on %s: %d", networkKey, errorCode)
                }
            }

        registrationListeners[networkKey] = listener
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && targetNetwork != null) {
                nsdManager.registerService(
                    serviceInfo,
                    NsdManager.PROTOCOL_DNS_SD,
                    ContextCompat.getMainExecutor(context),
                    listener,
                )
            } else {
                nsdManager.registerService(
                    serviceInfo,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
            }
        }.onFailure { error ->
            registrationListeners.remove(networkKey, listener)
            registeredServiceNames.remove(networkKey)
            logNsdOperationFailure(error, "Failed to register NSD service on $networkKey")
        }.isSuccess
    }

    override fun unregisterService(networkKey: String) {
        val listener = registrationListeners.remove(networkKey) ?: return
        runCatching { nsdManager.unregisterService(listener) }
            .onFailure { error -> logNsdOperationFailure(error, "Failed to unregister NSD service on $networkKey") }
        registeredServiceNames.remove(networkKey)
    }

    override fun unregisterAll() {
        registrationListeners.keys.toList().forEach(::unregisterService)
    }

    override fun startDiscovery(
        networkKey: String,
        uuid: String,
        targetNetwork: Network?,
    ): Boolean {
        this.localUuid = uuid
        stopDiscovery(networkKey)

        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Timber.tag(TAG).d("Discovery started on %s for %s", networkKey, serviceType)
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Timber.tag(TAG).d("Service found on %s: %s", networkKey, serviceInfo.serviceName)
                    if (serviceInfo.serviceName in registeredServiceNames.values) return

                    resolveService(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Timber.tag(TAG).d("Service lost on %s: %s", networkKey, serviceInfo.serviceName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val key = callbackKey(serviceInfo)
                        serviceInfoCallbacks.remove(key)?.let { callback ->
                            runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
                                .onFailure { Timber.tag(TAG).d(it, "Ignore callback unregister failure for $key") }
                        }
                    }
                    val lostName = serviceInfo.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                    _discoveredDevices.update { list ->
                        list.filter { it.name != lostName }
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Timber.tag(TAG).d("Discovery stopped on %s for %s", networkKey, serviceType)
                }

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Timber.tag(TAG).e("Start discovery failed on %s: %d", networkKey, errorCode)
                    discoveryListeners.remove(networkKey, this)
                    onDiscoveryStartFailed()
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Timber.tag(TAG).e("Stop discovery failed on %s: %d", networkKey, errorCode)
                }
            }

        discoveryListeners[networkKey] = listener
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && targetNetwork != null) {
                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    targetNetwork,
                    ContextCompat.getMainExecutor(context),
                    listener,
                )
            } else {
                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
            }
        }.onFailure { error ->
            discoveryListeners.remove(networkKey, listener)
            logNsdOperationFailure(error, "Failed to start NSD discovery on $networkKey")
        }.isSuccess
    }

    override fun stopDiscovery(networkKey: String) {
        val listener = discoveryListeners.remove(networkKey) ?: return
        runCatching { nsdManager.stopServiceDiscovery(listener) }
            .onFailure { error -> logNsdOperationFailure(error, "Failed to stop NSD discovery on $networkKey") }
    }

    override fun stopAllDiscovery() {
        discoveryListeners.keys.toList().forEach(::stopDiscovery)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfoCallbacks.values.toList().forEach { callback ->
                runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
                    .onFailure { Timber.tag(TAG).d(it, "Ignore callback unregister failure") }
            }
            serviceInfoCallbacks.clear()
        }
        _discoveredDevices.value = emptyList()
    }

    override fun mergeDiscoveredDevices(devices: List<DiscoveredDevice>) {
        if (devices.isEmpty()) return
        _discoveredDevices.update { existing ->
            val incomingKeys = devices.map { device -> "${device.host}:${device.port}" }.toSet()
            existing.filterNot { device -> "${device.host}:${device.port}" in incomingKeys } + devices
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveServiceApi34(serviceInfo)
            return
        }
        resolveServiceLegacy(serviceInfo)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveServiceApi34(serviceInfo: NsdServiceInfo) {
        val key = callbackKey(serviceInfo)

        val callback =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    handleResolvedService(info)
                }

                override fun onServiceLost() {
                    val name = serviceInfo.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                    _discoveredDevices.update { list -> list.filter { it.name != name } }
                }

                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Timber
                        .tag(TAG)
                        .e("ServiceInfo callback registration failed for ${serviceInfo.serviceName}: $errorCode")
                    serviceInfoCallbacks.remove(key, this)
                }

                override fun onServiceInfoCallbackUnregistered() {
                    serviceInfoCallbacks.remove(key, this)
                }
            }

        if (serviceInfoCallbacks.putIfAbsent(key, callback) != null) {
            return
        }

        runCatching {
            nsdManager.registerServiceInfoCallback(
                serviceInfo,
                ContextCompat.getMainExecutor(context),
                callback,
            )
        }.onFailure { error ->
            logNsdOperationFailure(error, "Failed to register ServiceInfo callback for ${serviceInfo.serviceName}")
            serviceInfoCallbacks.remove(key, callback)
        }
    }

    private fun resolveServiceLegacy(serviceInfo: NsdServiceInfo) {
        val listener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Timber.tag(TAG).e("Resolve failed for ${info.serviceName}: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    handleResolvedService(info)
                }
            }

        runCatching {
            val resolveService =
                NsdManager::class.java.getMethod(
                    "resolveService",
                    NsdServiceInfo::class.java,
                    NsdManager.ResolveListener::class.java,
            )
            resolveService.invoke(nsdManager, serviceInfo, listener)
        }.onFailure { error ->
            logNsdOperationFailure(error, "Failed to resolve service for ${serviceInfo.serviceName}")
        }
    }

    private fun handleResolvedService(info: NsdServiceInfo) {
        val device =
            mapResolvedLanShareDevice(
                serviceName = info.serviceName,
                hostAddresses = resolvedHostAddresses(info),
                port = info.port,
                attributes = info.attributes,
                localUuid = localUuid,
            )
        if (device == null) {
            Timber.tag(TAG).d("Ignored unresolved or self NSD service: ${info.serviceName}")
            return
        }

        Timber.tag(TAG).d("Resolved: ${device.name} at ${device.host}:${device.port}")
        _discoveredDevices.update { list ->
            // Deduplicate by host to prevent multiple entries for the same device (e.g. after rename)
            val existing = list.filter { it.host != device.host }
            existing + device
        }
    }

    companion object {
        const val SERVICE_TYPE = "_lomo-share._tcp."
        const val SERVICE_NAME_PREFIX = "Lomo-"
        private const val TAG = "NsdDiscovery"
    }
}
