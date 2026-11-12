package com.lomo.app.feature.memo

import android.location.Location
import android.os.SystemClock
import java.util.concurrent.TimeUnit

internal fun locationAgeMillis(
    location: Location,
    nowElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
    nowWallClockMillis: Long = System.currentTimeMillis(),
): Long {
    val elapsedNanos = location.elapsedRealtimeNanos
    if (elapsedNanos > 0L && nowElapsedNanos >= elapsedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(nowElapsedNanos - elapsedNanos)
    }
    val time = location.time
    if (time > 0L && nowWallClockMillis >= time) {
        return nowWallClockMillis - time
    }
    return Long.MAX_VALUE
}

internal fun isLocationFresh(
    location: Location,
    thresholdMillis: Long = FRESH_LOCATION_CACHE_THRESHOLD_MS,
    nowElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
    nowWallClockMillis: Long = System.currentTimeMillis(),
): Boolean = locationAgeMillis(location, nowElapsedNanos, nowWallClockMillis) <= thresholdMillis

internal fun isLocationWithinFallbackMaxAge(
    location: Location,
    maxAgeMillis: Long = MAX_FALLBACK_LOCATION_AGE_MS,
    nowElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
    nowWallClockMillis: Long = System.currentTimeMillis(),
): Boolean = locationAgeMillis(location, nowElapsedNanos, nowWallClockMillis) <= maxAgeMillis
