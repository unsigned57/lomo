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
class S3SyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataStore: LomoDataStore,
    ) {
        suspend fun reschedule() {
            val enabled = dataStore.s3SyncEnabled.first()
            val autoSyncEnabled = dataStore.s3AutoSyncEnabled.first()
            val workManager = WorkManager.getInstance(context)
            if (!enabled || !autoSyncEnabled) {
                cancel()
                return
            }

            val interval = dataStore.s3AutoSyncInterval.first()
            val duration = parseInterval(interval)
            val request =
                PeriodicWorkRequestBuilder<S3SyncWorker>(duration)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()

            workManager.enqueueUniquePeriodicWork(
                S3SyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request,
            )
            Timber.d("S3 auto-sync scheduled with interval: %s", interval)
        }

        fun cancel() {
            WorkManager.getInstance(context).cancelUniqueWork(S3SyncWorker.WORK_NAME)
            Timber.d("S3 auto-sync cancelled")
        }

        private fun parseInterval(interval: String): Duration =
            AUTO_SYNC_INTERVALS[interval] ?: DEFAULT_AUTO_SYNC_INTERVAL

        private companion object {
            private val AUTO_SYNC_INTERVALS =
                mapOf(
                    "30min" to Duration.ofMinutes(30),
                    "1h" to Duration.ofHours(1),
                    "6h" to Duration.ofHours(6),
                    "12h" to Duration.ofHours(12),
                    "24h" to Duration.ofHours(24),
                )
            private val DEFAULT_AUTO_SYNC_INTERVAL = Duration.ofHours(1)
        }
    }
