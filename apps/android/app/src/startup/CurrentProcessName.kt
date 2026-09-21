package com.lomo.app.startup

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

internal fun currentProcessName(context: Context): String {
    val named =
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            readProcSelfCmdline()
        }
    return named.ifEmpty { context.packageName }
}

private fun readProcSelfCmdline(): String {
    // behavior-contract: full-load-ok: /proc/self/cmdline is a tiny bounded kernel file
    val bytes = File("/proc/self/cmdline").readBytes()
    val end = bytes.indexOf(0)
    val slice = if (end >= 0) bytes.copyOfRange(0, end) else bytes
    return slice.toString(Charsets.UTF_8).trim()
}
