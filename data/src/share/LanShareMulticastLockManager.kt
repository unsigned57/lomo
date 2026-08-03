package com.lomo.data.share

import android.net.wifi.WifiManager

internal class LanShareMulticastLockManager(
    private val wifiManager: WifiManager?,
) {
    private var multicastLock: WifiManager.MulticastLock? = null

    fun acquire() {
        if (wifiManager == null) return
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("lomo_share_lock").also {
                it.setReferenceCounted(true)
            }
        }
        multicastLock?.acquire()
    }

    fun releaseIfHeld() {
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
    }
}
