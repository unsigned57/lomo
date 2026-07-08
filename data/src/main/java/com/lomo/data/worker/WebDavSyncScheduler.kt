package com.lomo.data.worker

import android.content.Context
import androidx.work.WorkManager
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.SyncScheduledWork
import com.lomo.data.sync.WebDavSyncWorkPolicyPlanner
import kotlinx.coroutines.flow.first
import timber.log.Timber

class WebDavSyncScheduler(
    private val context: Context,
    private val dataStore: LomoDataStore,
    private val policyPlanner: WebDavSyncWorkPolicyPlanner,
    private val scheduledWorkEnqueuer: WebDavScheduledSyncWorkEnqueuer,
) {
    suspend fun reschedule() {
        val enabled = dataStore.webDavSyncEnabled.first()
        val autoSyncEnabled = dataStore.webDavAutoSyncEnabled.first()
        if (!enabled || !autoSyncEnabled) {
            cancel()
            return
        }

        val interval = dataStore.webDavAutoSyncInterval.first()
        scheduledWorkEnqueuer.enqueue(policyPlanner.planAutoSchedule(interval).scheduledWork)
        Timber.d("WebDAV auto-sync scheduled with interval: %s", interval)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WebDavSyncWorker.WORK_NAME)
        Timber.d("WebDAV auto-sync cancelled")
    }
}

interface WebDavScheduledSyncWorkEnqueuer {
    suspend fun enqueue(work: List<SyncScheduledWork>)
}

class WebDavWorkManagerScheduledSyncWorkEnqueuer(
    private val context: Context,
) : WebDavScheduledSyncWorkEnqueuer {
    override suspend fun enqueue(work: List<SyncScheduledWork>) {
        val workManager = WorkManager.getInstance(context)
        work.forEach { scheduledWork ->
            workManager.enqueueSyncScheduledWork<WebDavSyncWorker>(scheduledWork)
        }
    }
}
