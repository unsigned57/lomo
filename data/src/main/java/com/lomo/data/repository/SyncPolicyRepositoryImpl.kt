package com.lomo.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lomo.data.worker.GitSyncScheduler
import com.lomo.data.worker.SyncWorker
import com.lomo.domain.repository.SyncPolicyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncPolicyRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val gitSyncScheduler: GitSyncScheduler,
    ) : SyncPolicyRepository {
        override fun ensureCoreSyncActive() {
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

        override suspend fun applyGitSyncPolicy() {
            gitSyncScheduler.reschedule()
        }
    }
