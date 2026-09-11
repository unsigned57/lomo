package com.lomo.app

import android.content.Context
import android.content.pm.ApplicationInfo

object AppBuildInfo {
    fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
