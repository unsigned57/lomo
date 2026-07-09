package com.lomo.app.feature.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

internal fun mainMemoLocationPermissions(): Array<String> =
    arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

internal fun mainMemoAttachLocationCommand(
    context: Context,
    onPermissionRequired: () -> Unit,
    onLocationMarkdown: (String) -> Unit,
): () -> Unit =
    {
        val hasPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            appendLastKnownLocation(context = context, onLocationMarkdown = onLocationMarkdown)
        } else {
            onPermissionRequired()
        }
    }

internal fun appendLastKnownLocation(
    context: Context,
    onLocationMarkdown: (String) -> Unit,
) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
    val hasPermission =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    if (!hasPermission) return
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    for (provider in providers) {
        // behavior-contract: silent-result-ok: provider may throw SecurityException; loop tries next
        val location = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        if (location != null) {
            onLocationMarkdown(formatMemoGeoUri(location.latitude, location.longitude))
            return
        }
    }
}
