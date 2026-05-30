package com.lomo.app.feature.share

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.lomo.app.CapabilityGateId
import com.lomo.app.CapabilityGatePolicies
import com.lomo.app.CapabilityPermissionNames
import com.lomo.app.CapabilityRecoveryAction
import com.lomo.app.CapabilityRecoveryDecision
import com.lomo.app.CapabilityRuntimePermissionPlan

internal const val ACCESS_LOCAL_NETWORK_PERMISSION = CapabilityPermissionNames.AccessLocalNetwork
internal const val NEARBY_WIFI_DEVICES_PERMISSION = CapabilityPermissionNames.NearbyWifiDevices

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
    lanShareNetworkPermissionPlan().requiredPermissions(
        sdkInt = sdkInt,
        isPermissionRecognized = isPermissionRecognized,
    )

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
    lanShareNetworkPermissionPlan().isGrantSatisfied(
        requiredPermissions = requiredPermissions,
        permissionResults = permissionResults,
        hasCurrentPermissions = hasCurrentPermissions,
    )

internal fun lanShareNetworkPermissionPlan(): CapabilityRuntimePermissionPlan =
    requireNotNull(
        CapabilityGatePolicies.requirePolicy(CapabilityGateId.LocalNetwork)
            .recoveryPlan
            .runtimePermissionPlan,
    ) {
        "Local Network capability must define a runtime permission plan."
    }

internal fun lanSharePermissionRecoveryAction(
    decision: CapabilityRecoveryDecision,
): CapabilityRecoveryAction? =
    CapabilityGatePolicies.requirePolicy(CapabilityGateId.LocalNetwork)
        .recoveryPlan
        .fallbackAction(decision)

internal fun canRetryLanSharePermissionRecovery(): Boolean =
    CapabilityGatePolicies.requirePolicy(CapabilityGateId.LocalNetwork)
        .recoveryPlan
        .canRetryAfterRecovery
