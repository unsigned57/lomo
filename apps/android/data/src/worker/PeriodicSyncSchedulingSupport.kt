package com.lomo.data.worker

import androidx.work.BackoffPolicy
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
import com.lomo.data.sync.SyncWorkBackoffPolicy
import com.lomo.data.sync.SyncWorkCadence
import com.lomo.data.sync.SyncWorkNetworkRequirement

internal const val SYNC_WORK_MAX_RETRY_ATTEMPTS_INPUT_KEY = "sync_work_max_retry_attempts"

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
    scheduledWork: SyncScheduledWork,
    inputData: Data? = null,
): PeriodicWorkRequest {
    val cadence =
        scheduledWork.cadence as? SyncWorkCadence.Periodic
            ?: error("Periodic sync WorkRequest requires periodic scheduled work")
    return PeriodicWorkRequestBuilder<T>(cadence.interval)
        .applyScheduledWorkPolicy(
            scheduledWork = scheduledWork,
            inputData = inputData,
        ).build()
}

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
                    scheduledWork = scheduledWork,
                    inputData = inputData,
                ),
            )

        SyncWorkCadence.OneTime ->
            enqueueUniqueWork(
                scheduledWork.uniqueWorkName,
                scheduledWork.existingWorkPolicy.toOneTimeWorkPolicy(),
                buildOneTimeSyncWorkRequest<T>(
                    scheduledWork = scheduledWork,
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
    scheduledWork: SyncScheduledWork,
    inputData: Data? = null,
): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<T>()
        .applyScheduledWorkPolicy(
            scheduledWork = scheduledWork,
            inputData = inputData,
        ).build()

private fun <BuilderT : androidx.work.WorkRequest.Builder<BuilderT, *>> BuilderT.applyScheduledWorkPolicy(
    scheduledWork: SyncScheduledWork,
    inputData: Data?,
): BuilderT {
    setInputData(inputData.withRetryPolicy(scheduledWork))
    setConstraints(scheduledWork.networkRequirement.toWorkConstraints())
    setBackoffCriteria(
        scheduledWork.retryPolicy.backoffPolicy.toWorkBackoffPolicy(),
        scheduledWork.retryPolicy.backoffDelay,
    )
    return this
}

private fun Data?.withRetryPolicy(scheduledWork: SyncScheduledWork): Data =
    Data
        .Builder()
        .apply {
            this@withRetryPolicy?.let(::putAll)
            putInt(SYNC_WORK_MAX_RETRY_ATTEMPTS_INPUT_KEY, scheduledWork.retryPolicy.maxAttempts)
        }.build()

private fun SyncWorkBackoffPolicy.toWorkBackoffPolicy(): BackoffPolicy =
    when (this) {
        SyncWorkBackoffPolicy.Exponential -> BackoffPolicy.EXPONENTIAL
        SyncWorkBackoffPolicy.Linear -> BackoffPolicy.LINEAR
    }
