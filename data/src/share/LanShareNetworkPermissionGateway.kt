package com.lomo.data.share

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.lomo.domain.model.LanShareNetworkPermissionPolicy
import com.lomo.domain.model.LanShareNetworkPermissionRequirement
import kotlin.coroutines.cancellation.CancellationException

internal interface LanShareNetworkPermissionGateway {
    fun hasRequiredPermissions(): Boolean
}

internal class AndroidLanShareNetworkPermissionGateway(
    private val context: Context,
) : LanShareNetworkPermissionGateway {
    override fun hasRequiredPermissions(): Boolean =
        LanShareNetworkPermissionPolicy
            .requiredRequirements(
                sdkInt = Build.VERSION.SDK_INT,
                isRequirementRecognized = { requirement ->
                    isPermissionRecognized(requirement.toAndroidPermissionName())
                },
            ).all { requirement ->
                ContextCompat.checkSelfPermission(
                    context,
                    requirement.toAndroidPermissionName(),
                ) == PackageManager.PERMISSION_GRANTED
            }

    private fun isPermissionRecognized(permission: String): Boolean =
        runCatching {
            context.packageManager.getPermissionInfo(permission, 0)
            true
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            error !is PackageManager.NameNotFoundException
        }
}

private fun LanShareNetworkPermissionRequirement.toAndroidPermissionName(): String =
    when (this) {
        LanShareNetworkPermissionRequirement.AccessLocalNetwork -> ACCESS_LOCAL_NETWORK_PERMISSION
    }

private const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
