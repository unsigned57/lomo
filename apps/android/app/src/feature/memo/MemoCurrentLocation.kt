package com.lomo.app.feature.memo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

internal const val FRESH_LOCATION_CACHE_THRESHOLD_MS = 60_000L
internal const val MAX_FALLBACK_LOCATION_AGE_MS = 2 * 60 * 60 * 1000L
internal const val CACHED_LOCATION_REFRESH_TIMEOUT_MS = 1_000L
internal const val LIVE_LOCATION_TIMEOUT_MS = 8_000L

internal enum class MemoLocationFailure { PermissionDenied, Unavailable, TimedOut, InvalidFix }

internal sealed interface MemoLocationResult {
    data class Located(val coordinates: MemoCoordinates) : MemoLocationResult
    data class Failed(val reason: MemoLocationFailure) : MemoLocationResult
}

internal fun hasMemoLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

internal fun isCoordinatePermissionGranted(
    permissionResults: Map<String, Boolean>,
    hasCurrentPermission: Boolean,
): Boolean {
    if (hasCurrentPermission) return true
    return permissionResults[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
        permissionResults[Manifest.permission.ACCESS_COARSE_LOCATION] == true
}

/**
 * The platform fused provider is public API from Android 12 (S). Below S it is absent as a
 * domain state (`null`), never a probed provider name.
 */
private fun platformFusedLocationProvider(): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null

internal fun resolveCandidateLocationProviders(
    enabledProviders: Collection<String>,
    hasFinePermission: Boolean,
    platformFusedProvider: String? = platformFusedLocationProvider(),
): List<String> = buildList {
    if (platformFusedProvider != null && platformFusedProvider in enabledProviders) {
        add(platformFusedProvider)
    }
    if (hasFinePermission && LocationManager.GPS_PROVIDER in enabledProviders) {
        add(LocationManager.GPS_PROVIDER)
    }
    if (LocationManager.NETWORK_PROVIDER in enabledProviders) {
        add(LocationManager.NETWORK_PROVIDER)
    }
}

/**
 * Behavior Contract: foreground one-shot direct coordinate acquisition.
 * Valid fresh caches insert immediately. Older caches within the existing two-hour fallback
 * window allow one second of refresh; a cold start retains the eight-second limit for active high-accuracy
 * updates. Every provider listener is released on success, timeout, cancellation or provider loss.
 * Malformed or stale live fixes never end the race against other providers.
 */
internal suspend fun readCurrentMemoLocation(
    context: Context,
    mainDispatcher: CoroutineDispatcher,
): MemoLocationResult {
    if (!hasMemoLocationPermission(context)) return MemoLocationResult.Failed(MemoLocationFailure.PermissionDenied)
    val manager = context.getSystemService(LocationManager::class.java)
        ?: return MemoLocationResult.Failed(MemoLocationFailure.Unavailable)
    if (!LocationManagerCompat.isLocationEnabled(manager)) {
        return MemoLocationResult.Failed(MemoLocationFailure.Unavailable)
    }

    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val (enabled, available) = try {
        manager.getProviders(true) to manager.getProviders(false)
    } catch (_: SecurityException) {
        return MemoLocationResult.Failed(MemoLocationFailure.PermissionDenied)
    }

    // A disabled live provider may still hold a useful cache. Read all permitted caches,
    // while activating only enabled providers below.
    val cached = readLastKnownLocations(manager, available, fine)
    val targetProviders = resolveCandidateLocationProviders(enabled, fine)
    if (targetProviders.isEmpty() && cached.isEmpty()) {
        return MemoLocationResult.Failed(MemoLocationFailure.Unavailable)
    }
    return acquireMemoLocation(
        cached,
        liveMemoLocations(context, manager, targetProviders, mainDispatcher),
        SystemClock::elapsedRealtimeNanos,
        System::currentTimeMillis,
    )
}

internal suspend fun acquireMemoLocation(
    cached: List<Location>,
    updates: Flow<Location>,
    nowElapsedNanos: () -> Long,
    nowWallClockMillis: () -> Long,
): MemoLocationResult {
    val bestLastKnown = selectCachedMemoLocation(cached, nowElapsedNanos(), nowWallClockMillis())
    if (bestLastKnown != null && isLocationFresh(
            bestLastKnown,
            nowElapsedNanos = nowElapsedNanos(),
            nowWallClockMillis = nowWallClockMillis(),
        )
    ) {
        return toLocationResult(bestLastKnown)
    }
    val timeout = if (bestLastKnown != null) CACHED_LOCATION_REFRESH_TIMEOUT_MS else LIVE_LOCATION_TIMEOUT_MS
    var failure = MemoLocationFailure.TimedOut
    val liveFix = try {
        withTimeoutOrNull(timeout) {
            updates.map { fix ->
                if (isLocationFresh(
                        fix,
                        nowElapsedNanos = nowElapsedNanos(),
                        nowWallClockMillis = nowWallClockMillis(),
                    )
                ) {
                    toLocationResult(fix)
                } else {
                    MemoLocationResult.Failed(MemoLocationFailure.InvalidFix)
                }
            }.onEach { result ->
                if (result is MemoLocationResult.Failed) failure = result.reason
            }.filterIsInstance<MemoLocationResult.Located>().firstOrNull()
        }
    } catch (_: SecurityException) {
        return MemoLocationResult.Failed(MemoLocationFailure.PermissionDenied)
    }
    if (liveFix != null) return liveFix
    if (bestLastKnown != null && isLocationWithinFallbackMaxAge(
            bestLastKnown,
            nowElapsedNanos = nowElapsedNanos(),
            nowWallClockMillis = nowWallClockMillis(),
        )
    ) {
        return toLocationResult(bestLastKnown)
    }
    return MemoLocationResult.Failed(failure)
}

private fun selectCachedMemoLocation(
    locations: List<Location>,
    nowElapsedNanos: Long,
    nowWallClockMillis: Long,
): Location? = locations.filter { location ->
    toLocationResult(location) is MemoLocationResult.Located &&
        isLocationWithinFallbackMaxAge(
            location,
            nowElapsedNanos = nowElapsedNanos,
            nowWallClockMillis = nowWallClockMillis,
        )
}.minByOrNull { location -> locationAgeMillis(location, nowElapsedNanos, nowWallClockMillis) }

private fun readLastKnownLocations(
    manager: LocationManager,
    available: Collection<String>,
    fine: Boolean,
): List<Location> {
    val providers = resolveCandidateLocationProviders(available, fine) +
        listOf(LocationManager.PASSIVE_PROVIDER).filter { it in available }
    return providers.mapNotNull { provider ->
        try {
            manager.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            // behavior-contract: silent-result-ok: provider permission can be revoked concurrently
            null
        } catch (_: IllegalArgumentException) {
            // behavior-contract: silent-result-ok: provider can disappear between enumeration and lookup
            null
        }
    }
}

/** One listener per provider: each registration has its own lifecycle, never a shared cancel signal. */
private fun liveMemoLocations(
    context: Context,
    manager: LocationManager,
    providers: List<String>,
    mainDispatcher: CoroutineDispatcher,
): Flow<Location> = callbackFlow {
    val listeners = mutableMapOf<String, LocationListenerCompat>()
    val executor = ContextCompat.getMainExecutor(context)
    val request = LocationRequestCompat.Builder(0L)
        .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        .setMinUpdateIntervalMillis(0L)
        .setDurationMillis(LIVE_LOCATION_TIMEOUT_MS)
        .build()
    providers.forEach { provider ->
        listeners[provider] = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }

            override fun onProviderDisabled(provider: String) {
                listeners.remove(provider)?.let(manager::removeUpdates)
                if (listeners.isEmpty()) close()
            }
        }
    }
    try {
        // Register from a snapshot because a provider can disappear while registration runs.
        for ((provider, listener) in listeners.toMap()) {
            try {
                LocationManagerCompat.requestLocationUpdates(manager, provider, request, executor, listener)
            } catch (denied: SecurityException) {
                close(denied)
                break
            } catch (_: IllegalArgumentException) {
                // behavior-contract: silent-result-ok: a disappeared provider leaves this request;
                // remaining providers still race, and no surviving provider reports a typed timeout.
                listeners.remove(provider)
            }
        }
        if (listeners.isEmpty()) close()
        awaitClose()
    } finally {
        listeners.values.forEach(manager::removeUpdates)
    }
}.flowOn(mainDispatcher)

private fun toLocationResult(location: Location): MemoLocationResult =
    when (val input = MemoCoordinates.parse(location.latitude.toString(), location.longitude.toString())) {
        is MemoCoordinateInput.Valid -> MemoLocationResult.Located(input.coordinates)
        else -> MemoLocationResult.Failed(MemoLocationFailure.InvalidFix)
    }
