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
class WebDavSyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataStore: LomoDataStore,
    ) {
        suspend fun reschedule() {
            val enabled = dataStore.webDavSyncEnabled.first()
            val autoSyncEnabled = dataStore.webDavAutoSyncEnabled.first()
            val workManager = WorkManager.getInstance(context)
            if (!enabled || !autoSyncEnabled) {
                cancel()
                return
            }

            val interval = dataStore.webDavAutoSyncInterval.first()
            val duration = parseRemoteAutoSyncInterval(interval)
            val request = buildPeriodicSyncWorkRequest<WebDavSyncWorker>(duration, connectedNetworkConstraints())

            workManager.enqueueUniquePeriodicWork(
                WebDavSyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request,
            )
            Timber.d("WebDAV auto-sync scheduled with interval: %s", interval)
        }

        fun cancel() {
            WorkManager.getInstance(context).cancelUniqueWork(WebDavSyncWorker.WORK_NAME)
            Timber.d("WebDAV auto-sync cancelled")
        }
    }
