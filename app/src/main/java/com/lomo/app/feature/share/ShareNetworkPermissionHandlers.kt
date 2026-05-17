package com.lomo.app.feature.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun rememberLanShareNetworkPermissionRequester(
    lanShareEnabled: Boolean,
    onPermissionGranted: () -> Unit,
    onPermissionDenied: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val requiredPermissions =
        remember(context) {
            requiredLanShareNetworkPermissions(
                isPermissionRecognized = lanSharePermissionRecognizer(context),
            )
        }
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            if (
                isLanSharePermissionRequestGranted(
                    requiredPermissions = requiredPermissions,
                    permissionResults = permissions,
                    hasCurrentPermissions = hasLanShareNetworkPermissions(context),
                )
            ) {
                onPermissionGranted()
            } else {
                onPermissionDenied()
            }
        }
    val requestPermissions: () -> Unit = {
        when {
            !lanShareEnabled -> Unit
            requiredPermissions.isEmpty() || hasLanShareNetworkPermissions(context) -> onPermissionGranted()
            else -> permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    LaunchedEffect(lanShareEnabled, requiredPermissions) {
        if (lanShareEnabled) {
            requestPermissions()
        }
    }
    return requestPermissions
}

internal fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}
