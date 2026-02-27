package com.lomo.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lomo.data.local.datastore.LomoDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Duration
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
            val duration = parseInterval(interval)

            val request =
                PeriodicWorkRequestBuilder<GitSyncWorker>(duration)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()

            workManager.enqueueUniquePeriodicWork(
                GitSyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request,
            )
            Timber.d("Git auto-sync scheduled with interval: %s", interval)
        }

        private fun parseInterval(interval: String): Duration =
            when (interval) {
                "30min" -> Duration.ofMinutes(30)
                "1h" -> Duration.ofHours(1)
                "6h" -> Duration.ofHours(6)
                "12h" -> Duration.ofHours(12)
                "24h" -> Duration.ofHours(24)
                else -> Duration.ofHours(1)
            }
    }
