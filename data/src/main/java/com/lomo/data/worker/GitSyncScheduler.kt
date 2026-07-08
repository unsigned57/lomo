package com.lomo.data.worker

import android.content.Context
import androidx.work.WorkManager
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.GitSyncWorkPolicyPlanner
import com.lomo.data.sync.SyncScheduledWork
import kotlinx.coroutines.flow.first
import timber.log.Timber

class GitSyncScheduler(
    private val context: Context,
    private val dataStore: LomoDataStore,
    private val policyPlanner: GitSyncWorkPolicyPlanner,
    private val scheduledWorkEnqueuer: GitScheduledSyncWorkEnqueuer,
) {
    suspend fun reschedule() {
        val gitEnabled = dataStore.gitSyncEnabled.first()
        val autoSyncEnabled = dataStore.gitAutoSyncEnabled.first()

        if (!gitEnabled || !autoSyncEnabled) {
            cancel()
            return
        }

        val interval = dataStore.gitAutoSyncInterval.first()
        scheduledWorkEnqueuer.enqueue(policyPlanner.planAutoSchedule(interval).scheduledWork)
        Timber.d("Git auto-sync scheduled with interval: %s", interval)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(GitSyncWorker.WORK_NAME)
        Timber.d("Git auto-sync cancelled")
    }
}

interface GitScheduledSyncWorkEnqueuer {
    suspend fun enqueue(work: List<SyncScheduledWork>)
}

class GitWorkManagerScheduledSyncWorkEnqueuer(
    private val context: Context,
) : GitScheduledSyncWorkEnqueuer {
    override suspend fun enqueue(work: List<SyncScheduledWork>) {
        val workManager = WorkManager.getInstance(context)
        work.forEach { scheduledWork ->
            workManager.enqueueSyncScheduledWork<GitSyncWorker>(scheduledWork)
        }
    }
}
