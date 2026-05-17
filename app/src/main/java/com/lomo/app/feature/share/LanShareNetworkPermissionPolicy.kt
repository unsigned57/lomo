package com.lomo.app.feature.share

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
internal const val NEARBY_WIFI_DEVICES_PERMISSION = "android.permission.NEARBY_WIFI_DEVICES"
private const val ANDROID_BAKLAVA_API_LEVEL = 36

internal typealias LanSharePermissionRecognizer = (String) -> Boolean

internal fun lanSharePermissionRecognizer(context: Context): LanSharePermissionRecognizer = { permission ->
    runCatching {
        context.packageManager.getPermissionInfo(permission, 0)
        true
    }.getOrElse { error ->
        if (error is PackageManager.NameNotFoundException) false else true
    }
}

internal fun requiredLanShareNetworkPermissions(
    sdkInt: Int = Build.VERSION.SDK_INT,
    isPermissionRecognized: LanSharePermissionRecognizer = { true },
): List<String> =
    buildList {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(NEARBY_WIFI_DEVICES_PERMISSION)
        }
        if (
            sdkInt >= ANDROID_BAKLAVA_API_LEVEL &&
            isPermissionRecognized(ACCESS_LOCAL_NETWORK_PERMISSION)
        ) {
            add(ACCESS_LOCAL_NETWORK_PERMISSION)
        }
    }

internal fun hasLanShareNetworkPermissions(
    context: Context,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Boolean =
    requiredLanShareNetworkPermissions(sdkInt, lanSharePermissionRecognizer(context)).all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

internal fun isLanSharePermissionRequestGranted(
    requiredPermissions: List<String>,
    permissionResults: Map<String, Boolean>,
    hasCurrentPermissions: Boolean,
): Boolean =
    requiredPermissions.isEmpty() ||
        requiredPermissions.all { permission -> permissionResults[permission] == true } ||
        hasCurrentPermissions
