package com.lomo.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

object AppBuildInfo {
    fun isDebuggable(context: Context): Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun benchmarkAnchorsEnabled(context: Context): Boolean = isDebuggable(context) ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context.applicationInfo.isProfileableByShell)
}
