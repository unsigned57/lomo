package com.lomo.data.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.lomo.data.local.datastore.LomoDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitSyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataStore: LomoDataStore,
    ) {
        suspend fun reschedule() {
            val gitEnabled = dataStore.gitSyncEnabled.first()
            val autoSyncEnabled = dataStore.gitAutoSyncEnabled.first()

            val workManager = WorkManager.getInstance(context)

            if (!gitEnabled || !autoSyncEnabled) {
                workManager.cancelUniqueWork(GitSyncWorker.WORK_NAME)
                Timber.d("Git auto-sync disabled, cancelled worker")
                return
            }

            val interval = dataStore.gitAutoSyncInterval.first()
            val duration = parseRemoteAutoSyncInterval(interval)

            val request = buildPeriodicSyncWorkRequest<GitSyncWorker>(duration, connectedNetworkConstraints())

            workManager.enqueueUniquePeriodicWork(
                GitSyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request,
            )
            Timber.d("Git auto-sync scheduled with interval: %s", interval)
        }

        fun cancel() {
            WorkManager.getInstance(context).cancelUniqueWork(GitSyncWorker.WORK_NAME)
            Timber.d("Git auto-sync cancelled")
        }
    }
