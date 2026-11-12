package com.lomo.data.engine

import android.content.Context
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import androidx.core.content.ContextCompat

/**
 * Process-lifetime registration owned by data, closed by Koin. Release processes have no
 * registration. A debug process is explicitly started by xtask before same-UID probe delivery.
 * The receiver's measurement factory remains deferred until a valid broadcast, avoiding eager
 * engine construction on the app's startup measurement path.
 */
internal class PerfProbeRegistration(private val context: Context, private val receiver: PerfProbeReceiver) :
    AutoCloseable {
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    init {
        if (debuggable) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(PerfProbeReceiver.probeAction(context)),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    override fun close() {
        if (debuggable) context.unregisterReceiver(receiver)
    }
}
