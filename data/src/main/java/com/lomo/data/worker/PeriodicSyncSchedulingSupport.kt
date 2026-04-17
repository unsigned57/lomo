package com.lomo.data.worker

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import java.time.Duration

internal val DEFAULT_REMOTE_AUTO_SYNC_INTERVAL: Duration = Duration.ofHours(1)

private const val AUTO_SYNC_MINUTES_30 = 30L
private const val AUTO_SYNC_HOURS_1 = 1L
private const val AUTO_SYNC_HOURS_6 = 6L
private const val AUTO_SYNC_HOURS_12 = 12L
private const val AUTO_SYNC_HOURS_24 = 24L

private val REMOTE_AUTO_SYNC_INTERVALS =
    mapOf(
        "30min" to Duration.ofMinutes(AUTO_SYNC_MINUTES_30),
        "1h" to Duration.ofHours(AUTO_SYNC_HOURS_1),
        "6h" to Duration.ofHours(AUTO_SYNC_HOURS_6),
        "12h" to Duration.ofHours(AUTO_SYNC_HOURS_12),
        "24h" to Duration.ofHours(AUTO_SYNC_HOURS_24),
    )

internal fun parseRemoteAutoSyncInterval(interval: String): Duration =
    REMOTE_AUTO_SYNC_INTERVALS[interval] ?: DEFAULT_REMOTE_AUTO_SYNC_INTERVAL

internal fun connectedNetworkConstraints(): Constraints =
    Constraints
        .Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

internal fun unmeteredChargingConstraints(): Constraints =
    Constraints
        .Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresCharging(true)
        .build()

internal inline fun <reified T : ListenableWorker> buildPeriodicSyncWorkRequest(
    interval: Duration,
    constraints: Constraints,
    inputData: Data? = null,
): PeriodicWorkRequest =
    PeriodicWorkRequestBuilder<T>(interval)
        .apply {
            inputData?.let(::setInputData)
            setConstraints(constraints)
        }.build()

internal inline fun <reified T : ListenableWorker> buildOneTimeSyncWorkRequest(
    constraints: Constraints,
    inputData: Data? = null,
): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<T>()
        .apply {
            inputData?.let(::setInputData)
            setConstraints(constraints)
        }.build()
