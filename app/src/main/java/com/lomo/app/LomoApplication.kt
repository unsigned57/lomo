package com.lomo.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.lomo.app.BuildConfig
import com.lomo.domain.repository.SyncSchedulerRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class LomoApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncSchedulerRepository: SyncSchedulerRepository
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Defer non-critical worker registration off the main thread.
        appScope.launch {
            try {
                syncSchedulerRepository.ensureLocalPeriodicSyncScheduled()
            } catch (e: Exception) {
                Timber.e(e, "Failed to schedule sync")
            }

            try {
                syncSchedulerRepository.rescheduleGitAutoSync()
            } catch (e: Exception) {
                Timber.e(e, "Failed to schedule git sync")
            }
        }
    }
}
