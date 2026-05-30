package com.lomo.data.worker

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lomo.data.sync.SyncExistingWorkPolicy
import com.lomo.data.sync.SyncScheduledWork
import com.lomo.data.sync.SyncWorkCadence
import com.lomo.data.sync.SyncWorkNetworkRequirement
import java.time.Duration

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

internal inline fun <reified T : ListenableWorker> WorkManager.enqueueSyncScheduledWork(
    scheduledWork: SyncScheduledWork,
    inputData: Data? = null,
) {
    when (val cadence = scheduledWork.cadence) {
        is SyncWorkCadence.Periodic ->
            enqueueUniquePeriodicWork(
                scheduledWork.uniqueWorkName,
                scheduledWork.existingWorkPolicy.toPeriodicWorkPolicy(),
                buildPeriodicSyncWorkRequest<T>(
                    interval = cadence.interval,
                    constraints = scheduledWork.networkRequirement.toWorkConstraints(),
                    inputData = inputData,
                ),
            )

        SyncWorkCadence.OneTime ->
            enqueueUniqueWork(
                scheduledWork.uniqueWorkName,
                scheduledWork.existingWorkPolicy.toOneTimeWorkPolicy(),
                buildOneTimeSyncWorkRequest<T>(
                    constraints = scheduledWork.networkRequirement.toWorkConstraints(),
                    inputData = inputData,
                ),
            )
    }
}

private fun SyncExistingWorkPolicy.toPeriodicWorkPolicy(): ExistingPeriodicWorkPolicy =
    when (this) {
        SyncExistingWorkPolicy.Replace -> ExistingPeriodicWorkPolicy.REPLACE
    }

private fun SyncExistingWorkPolicy.toOneTimeWorkPolicy(): ExistingWorkPolicy =
    when (this) {
        SyncExistingWorkPolicy.Replace -> ExistingWorkPolicy.REPLACE
    }

private fun SyncWorkNetworkRequirement.toWorkConstraints(): Constraints =
    when (this) {
        SyncWorkNetworkRequirement.Connected -> connectedNetworkConstraints()
        SyncWorkNetworkRequirement.UnmeteredCharging -> unmeteredChargingConstraints()
    }

internal inline fun <reified T : ListenableWorker> buildOneTimeSyncWorkRequest(
    constraints: Constraints,
    inputData: Data? = null,
): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<T>()
        .apply {
            inputData?.let(::setInputData)
            setConstraints(constraints)
        }.build()
