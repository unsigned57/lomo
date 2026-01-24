package com.lomo.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.*
import com.lomo.app.BuildConfig
import com.lomo.data.worker.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class LomoApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        try {
            schedulePeriodicSync()
        } catch (e: Exception) {
            Timber.e(e, "Failed to schedule sync")
        }
    }

    private fun schedulePeriodicSync() {
        val syncRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(
                            NetworkType.NOT_REQUIRED,
                        ) // Memos are local
                        .build(),
                ).build()

        WorkManager
            .getInstance(this)
            .enqueueUniquePeriodicWork(
                SyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest,
            )
    }
}
