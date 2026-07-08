package com.lomo.data.worker

import android.content.Context
import androidx.work.WorkManager
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.repository.S3ScheduledSyncWorkEnqueuer
import com.lomo.data.repository.S3_SYNC_WORK_INTENT_PARAMETER
import com.lomo.data.repository.S3SyncWorkIntent
import com.lomo.data.sync.S3SyncWorkPolicyPlanner
import com.lomo.data.sync.SyncScheduledWork
import com.lomo.data.sync.SyncWorkPayload
import kotlinx.coroutines.flow.first
import timber.log.Timber

class S3SyncScheduler(
    private val context: Context,
    private val dataStore: LomoDataStore,
    private val policyPlanner: S3SyncWorkPolicyPlanner,
) : S3ScheduledSyncWorkEnqueuer {
    suspend fun reschedule() {
        val enabled = dataStore.s3SyncEnabled.first()
        val autoSyncEnabled = dataStore.s3AutoSyncEnabled.first()
        if (!enabled || !autoSyncEnabled) {
            cancel()
            return
        }

        val interval = dataStore.s3AutoSyncInterval.first()
        enqueue(policyPlanner.planAutoSchedule(interval).scheduledWork)
        Timber.d("S3 auto-sync scheduled with interval: %s", interval)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(S3SyncWorker.WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(S3SyncWorker.RECONCILE_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(S3SyncWorker.RECONCILE_CATCH_UP_WORK_NAME)
        Timber.d("S3 auto-sync cancelled")
    }

    override suspend fun enqueue(work: List<SyncScheduledWork>) {
        val workManager = WorkManager.getInstance(context)
        work.forEach { scheduledWork ->
            workManager.enqueueSyncScheduledWork<S3SyncWorker>(
                scheduledWork = scheduledWork,
                inputData = scheduledWork.payload.toS3InputData(),
            )
        }
    }
}

private fun SyncWorkPayload.toS3InputData(): androidx.work.Data =
    when (this) {
        is SyncWorkPayload.ProviderParameters ->
            S3SyncWorker.inputData(
                values[S3_SYNC_WORK_INTENT_PARAMETER]
                    ?.let { raw -> enumValues<S3SyncWorkIntent>().firstOrNull { candidate -> candidate.name == raw } }
                    ?: error("S3 scheduler requires S3 work intent payload"),
            )
        SyncWorkPayload.StandardRemoteSync -> error("S3 scheduler requires provider parameters")
    }
