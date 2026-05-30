package com.lomo.data.worker

import android.content.Context
import androidx.work.WorkManager
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.SyncScheduledWork
import com.lomo.data.sync.WebDavSyncWorkPolicyPlanner
import dagger.Binds
import dagger.Module
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavSyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
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

@Singleton
class WebDavWorkManagerScheduledSyncWorkEnqueuer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : WebDavScheduledSyncWorkEnqueuer {
        override suspend fun enqueue(work: List<SyncScheduledWork>) {
            val workManager = WorkManager.getInstance(context)
            work.forEach { scheduledWork ->
                workManager.enqueueSyncScheduledWork<WebDavSyncWorker>(scheduledWork)
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class WebDavSyncSchedulingModule {
    @Binds
    abstract fun bindWebDavScheduledSyncWorkEnqueuer(
        enqueuer: WebDavWorkManagerScheduledSyncWorkEnqueuer,
    ): WebDavScheduledSyncWorkEnqueuer
}
