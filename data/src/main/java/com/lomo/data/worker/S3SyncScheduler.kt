package com.lomo.data.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.repository.S3RefreshCatchUpScheduler
import com.lomo.domain.model.S3SyncScanPolicy
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
        private val reconcileScheduler: S3ReconcileScheduler,
    ) : S3RefreshCatchUpScheduler {
        suspend fun reschedule() {
            val enabled = dataStore.s3SyncEnabled.first()
            val autoSyncEnabled = dataStore.s3AutoSyncEnabled.first()
            val workManager = WorkManager.getInstance(context)
            if (!enabled || !autoSyncEnabled) {
                cancel()
                return
            }

            val interval = dataStore.s3AutoSyncInterval.first()
            val schedulePlan = reconcileScheduler.buildSchedulePlan(interval)
            val fastRequest =
                buildPeriodicSyncWorkRequest<S3SyncWorker>(
                    interval = schedulePlan.fastInterval,
                    constraints = connectedNetworkConstraints(),
                    inputData = S3SyncWorker.inputData(S3SyncScanPolicy.FAST_ONLY),
                )
            val reconcileRequest =
                buildPeriodicSyncWorkRequest<S3SyncWorker>(
                    interval = schedulePlan.reconcileInterval,
                    constraints = unmeteredChargingConstraints(),
                    inputData = S3SyncWorker.inputData(S3SyncScanPolicy.FULL_RECONCILE),
                )

            workManager.enqueueUniquePeriodicWork(
                S3SyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                fastRequest,
            )
            workManager.enqueueUniquePeriodicWork(
                S3SyncWorker.RECONCILE_WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                reconcileRequest,
            )
            schedulePlan.catchUpPolicy?.let { policy ->
                workManager.enqueueUniqueWork(
                    S3SyncWorker.RECONCILE_CATCH_UP_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    buildOneTimeSyncWorkRequest<S3SyncWorker>(
                        constraints = catchUpConstraints(policy),
                        inputData = S3SyncWorker.inputData(policy),
                    ),
                )
            }
            Timber.d("S3 auto-sync scheduled with interval: %s", interval)
        }

        fun cancel() {
            WorkManager.getInstance(context).cancelUniqueWork(S3SyncWorker.WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(S3SyncWorker.RECONCILE_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(S3SyncWorker.RECONCILE_CATCH_UP_WORK_NAME)
            Timber.d("S3 auto-sync cancelled")
        }

        override suspend fun scheduleCatchUp(policy: S3SyncScanPolicy) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                S3SyncWorker.RECONCILE_CATCH_UP_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                buildOneTimeSyncWorkRequest<S3SyncWorker>(
                    constraints = catchUpConstraints(policy),
                    inputData = S3SyncWorker.inputData(policy),
                ),
            )
        }

        private fun catchUpConstraints(policy: S3SyncScanPolicy): androidx.work.Constraints =
            when (policy) {
                S3SyncScanPolicy.FULL_RECONCILE -> unmeteredChargingConstraints()

                S3SyncScanPolicy.FAST_ONLY,
                S3SyncScanPolicy.FAST_THEN_RECONCILE,
                -> connectedNetworkConstraints()
            }

    }

data class S3SyncSchedulePlan(
    val fastInterval: Duration,
    val reconcileInterval: Duration,
    val catchUpPolicy: S3SyncScanPolicy?,
)

data class S3RefreshSyncPlan(
    val foregroundPolicy: S3SyncScanPolicy,
    val catchUpPolicy: S3SyncScanPolicy?,
)
