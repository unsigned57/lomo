package com.lomo.data.share

import android.net.nsd.NsdServiceInfo
import android.os.Build
import timber.log.Timber
import java.net.InetAddress
import kotlin.coroutines.cancellation.CancellationException

internal fun callbackKey(serviceInfo: NsdServiceInfo): String =
    "${serviceInfo.serviceName}|${serviceInfo.serviceType}"

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
            runCatching {
                NsdServiceInfo::class.java
                    .getMethod("getHost")
                    .invoke(info) as? InetAddress
            }.getOrNull(),
        )
    }
