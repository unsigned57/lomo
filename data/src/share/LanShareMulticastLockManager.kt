package com.lomo.data.share

import android.net.wifi.WifiManager
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

internal class LanShareMulticastLockManager(
    private val wifiManager: WifiManager?,
) {
    private var multicastLock: WifiManager.MulticastLock? = null

    fun acquire() {
        runCatching {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("lomo_share_lock")
                multicastLock?.setReferenceCounted(true)
            }
            multicastLock?.acquire()
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Timber.tag("ShareServiceLifecycle").e(error, "Failed to acquire multicast lock")
        }
    }

    fun releaseIfHeld() {
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Timber.tag("ShareServiceLifecycle").e(error, "Failed to release multicast lock")
        }
    }
}
