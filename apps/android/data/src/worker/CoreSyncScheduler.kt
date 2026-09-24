package com.lomo.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration

/**
 * Single WorkManager enqueue exit for the periodic local memo refresh ([SyncWorker]).
 *
 * The refresh fact is local (projection rebuild); remote-sync scheduling is owned by
 * [RustSyncScheduler]. Repositories and settings never enqueue work directly — every
 * WorkManager mutation exits through this scheduler package (audit B03/B05).
 */
class CoreSyncScheduler(
    private val context: Context,
) {
    fun ensureActive() {
        val syncRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofHours(1))
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build(),
                ).build()

        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(
                SyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest,
            )
    }
}
