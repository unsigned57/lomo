package com.lomo.data.share

import android.content.Context
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
 * Owns the process-wide NSD advertisement and discovery session for LAN sharing.
 */
interface LanShareDiscoveryCoordinator {
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>

    fun registerService(
        port: Int,
        deviceName: String,
        uuid: String,
    ): Boolean

    fun unregisterService()

    fun startDiscovery(uuid: String): Boolean

    fun stopDiscovery()

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
    private val listenerLock = Any()
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()
    private val serviceInfoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
    private val endpointRegistry = LanShareNsdEndpointRegistry()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var localUuid: String? = null

    override fun registerService(
        port: Int,
        deviceName: String,
        uuid: String,
    ): Boolean {
        unregisterService()
        val serviceInfo =
            NsdServiceInfo().apply {
                serviceName = "$SERVICE_NAME_PREFIX$deviceName"
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("uuid", uuid)
            }
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Timber.tag(TAG).d("Service registered globally: %s", info.serviceName)
                }

                override fun onRegistrationFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Timber.tag(TAG).e("Global registration failed: %d", errorCode)
                    clearRegistrationListener(this)
                    onServiceRegistrationFailed()
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Timber.tag(TAG).d("Service unregistered globally: %s", info.serviceName)
                }

                override fun onUnregistrationFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Timber.tag(TAG).e("Global unregistration failed: %d", errorCode)
                }
            }
        synchronized(listenerLock) {
            registrationListener = listener
        }
        return runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            clearRegistrationListener(listener)
            logNsdOperationFailure(error, "Failed to register global NSD service")
        }.isSuccess
    }

    override fun unregisterService() {
        val listener =
            synchronized(listenerLock) {
                registrationListener.also { registrationListener = null }
            } ?: return
        runCatching { nsdManager.unregisterService(listener) }
            .onFailure { error -> logNsdOperationFailure(error, "Failed to unregister global NSD service") }
    }

    override fun startDiscovery(uuid: String): Boolean {
        localUuid = uuid
        stopDiscovery()
        localUuid = uuid
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Timber.tag(TAG).d("Global discovery started for %s", serviceType)
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Timber.tag(TAG).d("Service found globally: %s", serviceInfo.serviceName)
                    resolveService(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val serviceKey = callbackKey(serviceInfo)
                    Timber.tag(TAG).d("Service lost globally: %s", serviceInfo.serviceName)
                    unregisterServiceInfoCallback(serviceKey)
                    removeResolvedService(serviceKey)
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Timber.tag(TAG).d("Global discovery stopped for %s", serviceType)
                }

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Timber.tag(TAG).e("Global discovery start failed: %d", errorCode)
                    clearDiscoveryListener(this)
                    onDiscoveryStartFailed()
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Timber.tag(TAG).e("Global discovery stop failed: %d", errorCode)
                }
            }
        synchronized(listenerLock) {
            discoveryListener = listener
        }
        return runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            clearDiscoveryListener(listener)
            logNsdOperationFailure(error, "Failed to start global NSD discovery")
        }.isSuccess
    }

    override fun stopDiscovery() {
        val listener =
            synchronized(listenerLock) {
                discoveryListener.also { discoveryListener = null }
            }
        if (listener != null) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
                .onFailure { error -> logNsdOperationFailure(error, "Failed to stop global NSD discovery") }
        }
        unregisterAllServiceInfoCallbacks()
        endpointRegistry.clear()
        _discoveredDevices.value = emptyList()
    }

    override fun mergeDiscoveredDevices(devices: List<DiscoveredDevice>) {
        if (devices.isEmpty()) return
        _discoveredDevices.update { existing ->
            mergeLanShareDiscoveredDevices(existing = existing, incoming = devices)
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveServiceApi34(serviceInfo)
        } else {
            resolveServiceLegacy(serviceInfo)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveServiceApi34(serviceInfo: NsdServiceInfo) {
        val serviceKey = callbackKey(serviceInfo)
        val callback =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    handleResolvedService(serviceKey, info)
                }

                override fun onServiceLost() {
                    removeResolvedService(serviceKey)
                }

                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Timber.tag(TAG).e("ServiceInfo callback registration failed for %s: %d", serviceKey, errorCode)
                    serviceInfoCallbacks.remove(serviceKey, this)
                }

                override fun onServiceInfoCallbackUnregistered() {
                    serviceInfoCallbacks.remove(serviceKey, this)
                }
            }
        if (serviceInfoCallbacks.putIfAbsent(serviceKey, callback) != null) return
        runCatching {
            nsdManager.registerServiceInfoCallback(
                serviceInfo,
                ContextCompat.getMainExecutor(context),
                callback,
            )
        }.onFailure { error ->
            serviceInfoCallbacks.remove(serviceKey, callback)
            logNsdOperationFailure(error, "Failed to register ServiceInfo callback for $serviceKey")
        }
    }

    private fun resolveServiceLegacy(serviceInfo: NsdServiceInfo) {
        val serviceKey = callbackKey(serviceInfo)
        val listener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Timber.tag(TAG).e("Resolve failed for %s: %d", serviceKey, errorCode)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    handleResolvedService(serviceKey, info)
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
            logNsdOperationFailure(error, "Failed to resolve service for $serviceKey")
        }
    }

    private fun handleResolvedService(
        serviceKey: String,
        info: NsdServiceInfo,
    ) {
        val device =
            mapResolvedLanShareDevice(
                serviceName = info.serviceName,
                hostAddresses = resolvedHostAddresses(info),
                port = info.port,
                attributes = info.attributes,
                localUuid = localUuid,
            )
        if (device == null) {
            removeResolvedService(serviceKey)
            Timber.tag(TAG).d("Ignored invalid or self NSD service: %s", serviceKey)
            return
        }
        val staleEndpoint = endpointRegistry.record(serviceKey, device)
        _discoveredDevices.update { existing ->
            mergeLanShareDiscoveredDevices(
                existing = removeLanShareEndpoint(existing, staleEndpoint),
                incoming = listOf(device),
            )
        }
        Timber.tag(TAG).d("Resolved %s at %s:%d", device.name, device.host, device.port)
    }

    private fun removeResolvedService(serviceKey: String) {
        val endpointKey = endpointRegistry.remove(serviceKey)
        _discoveredDevices.update { devices -> removeLanShareEndpoint(devices, endpointKey) }
    }

    private fun unregisterServiceInfoCallback(serviceKey: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val callback = serviceInfoCallbacks.remove(serviceKey) ?: return
        runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
            .onFailure { error -> Timber.tag(TAG).d(error, "Ignore callback unregister failure for $serviceKey") }
    }

    private fun unregisterAllServiceInfoCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfoCallbacks.clear()
            return
        }
        serviceInfoCallbacks.keys.toList().forEach(::unregisterServiceInfoCallback)
    }

    private fun clearRegistrationListener(listener: NsdManager.RegistrationListener) {
        synchronized(listenerLock) {
            if (registrationListener === listener) registrationListener = null
        }
    }

    private fun clearDiscoveryListener(listener: NsdManager.DiscoveryListener) {
        synchronized(listenerLock) {
            if (discoveryListener === listener) discoveryListener = null
        }
    }

    companion object {
        const val SERVICE_TYPE = "_lomo-share._tcp."
        const val SERVICE_NAME_PREFIX = "Lomo-"
        private const val TAG = "NsdDiscovery"
    }
}
