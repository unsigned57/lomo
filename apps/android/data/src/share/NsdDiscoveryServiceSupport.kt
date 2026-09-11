package com.lomo.data.share

import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.lomo.domain.model.DiscoveredDevice
import timber.log.Timber
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

internal fun callbackKey(serviceInfo: NsdServiceInfo): String =
    "${serviceInfo.serviceName}|${serviceInfo.serviceType}"

internal class LanShareNsdEndpointRegistry {
    private val endpointsByServiceKey = ConcurrentHashMap<String, String>()

    fun record(
        serviceKey: String,
        device: DiscoveredDevice,
    ): String? = endpointsByServiceKey.put(serviceKey, device.lanShareEndpointKey())

    fun remove(serviceKey: String): String? = endpointsByServiceKey.remove(serviceKey)

    fun clear() {
        endpointsByServiceKey.clear()
    }
}

internal fun removeLanShareEndpoint(
    devices: List<DiscoveredDevice>,
    endpointKey: String?,
): List<DiscoveredDevice> =
    if (endpointKey == null) {
        devices
    } else {
        devices.filterNot { device -> device.lanShareEndpointKey() == endpointKey }
    }

internal fun logNsdOperationFailure(
    error: Throwable,
    message: String,
) {
    if (error is CancellationException) throw error
    Timber.tag("NsdDiscovery").e(error, message)
}

internal fun resolvedHostAddresses(info: NsdServiceInfo): List<InetAddress> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        info.hostAddresses
    } else {
        listOfNotNull(
            // behavior-contract: silent-result-ok: reflective getHost may fail; null filtered by listOfNotNull
            runCatching {
                NsdServiceInfo::class.java
                    .getMethod("getHost")
                    .invoke(info) as? InetAddress
            }.getOrNull(),
        )
    }
