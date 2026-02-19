package com.lomo.data.share

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.lomo.domain.model.DiscoveredDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

/**
 * Manages NSD (Network Service Discovery) for discovering and advertising
 * Lomo instances on the local network.
 */
class NsdDiscoveryService(
    private val context: Context,
) {
    companion object {
        const val SERVICE_TYPE = "_lomo-share._tcp."
        const val SERVICE_NAME_PREFIX = "Lomo-"
        private const val TAG = "NsdDiscovery"
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registeredServiceName: String? = null

    // --- Service Registration ---

    fun registerService(port: Int, deviceName: String, uuid: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME_PREFIX$deviceName"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("uuid", uuid)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                Timber.tag(TAG).d("Service registered: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Timber.tag(TAG).e("Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Timber.tag(TAG).d("Service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Timber.tag(TAG).e("Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to register NSD service")
        }
    }

    fun unregisterService() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to unregister NSD service")
        }
        registrationListener = null
        registeredServiceName = null
    }

    // --- Service Discovery ---

    private var localUuid: String? = null

    fun startDiscovery(uuid: String) {
        this.localUuid = uuid
        _discoveredDevices.value = emptyList()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Timber.tag(TAG).d("Discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Timber.tag(TAG).d("Service found: ${serviceInfo.serviceName}")
                // Don't resolve our own service
                if (serviceInfo.serviceName == registeredServiceName) return

                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Timber.tag(TAG).d("Service lost: ${serviceInfo.serviceName}")
                val lostName = serviceInfo.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                _discoveredDevices.update { list ->
                    list.filter { it.name != lostName }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Timber.tag(TAG).d("Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.tag(TAG).e("Start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.tag(TAG).e("Stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start NSD discovery")
        }
    }

    fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to stop NSD discovery")
        }
        discoveryListener = null
        _discoveredDevices.value = emptyList()
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Timber.tag(TAG).e("Resolve failed for ${info.serviceName}: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    // Filter for IPv4 only to ensure reachability
                    val hostAddress = info.host
                    if (hostAddress !is java.net.Inet4Address) {
                        Timber.tag(TAG).d("Ignored non-IPv4 host: $hostAddress")
                        return
                    }
                    val host = hostAddress.hostAddress
                    val port = info.port
                    val name = info.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                    
                    val uuidBytes = info.attributes["uuid"]
                    val remoteUuid = uuidBytes?.let { String(it, Charsets.UTF_8) }

                    if (localUuid != null && remoteUuid == localUuid) {
                        Timber.tag(TAG).d("Ignored self: $name ($remoteUuid)")
                        return
                    }

                    Timber.tag(TAG).d("Resolved: $name at $host:$port (uuid=$remoteUuid)")

                    val device = DiscoveredDevice(
                        name = name,
                        host = host,
                        port = port,
                    )

                    _discoveredDevices.update { list ->
                        // Deduplicate by host to prevent multiple entries for the same device (e.g. after rename)
                        val existing = list.filter { it.host != host }
                        existing + device
                    }
                }
            },
        )
    }
}
