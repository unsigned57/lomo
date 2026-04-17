package com.lomo.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncScanPolicy
import com.lomo.domain.repository.S3SyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class S3SyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val s3SyncRepository: S3SyncRepository,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result {
            Timber.d("%s started", WORKER_NAME)
            val policy = resolveScanPolicy(inputData)
            return when (val result = s3SyncRepository.sync(policy)) {
                is S3SyncResult.Success -> successWorkResult(WORKER_NAME, result.message)
                is S3SyncResult.Error -> errorWorkResult(WORKER_NAME, result.message)
                is S3SyncResult.Conflict -> conflictWorkResult(WORKER_NAME, result.message)
                S3SyncResult.NotConfigured -> skipWorkResult(WORKER_NAME, result)
            }
        }

        companion object {
            private const val WORKER_NAME = "S3SyncWorker"
            const val WORK_NAME = "com.lomo.data.worker.S3SyncWorker"
            const val RECONCILE_WORK_NAME = "com.lomo.data.worker.S3SyncWorker.RECONCILE"
            const val RECONCILE_CATCH_UP_WORK_NAME = "com.lomo.data.worker.S3SyncWorker.RECONCILE_CATCH_UP"
            internal const val INPUT_SCAN_POLICY = "scan_policy"

            internal fun inputData(
                policy: S3SyncScanPolicy,
            ): Data =
                Data
                    .Builder()
                    .putString(INPUT_SCAN_POLICY, policy.name)
                    .build()

            internal fun resolveScanPolicy(inputData: Data): S3SyncScanPolicy =
                inputData.getString(INPUT_SCAN_POLICY)
                    ?.let { raw ->
                        enumValues<S3SyncScanPolicy>().firstOrNull { candidate -> candidate.name == raw }
                    } ?: S3SyncScanPolicy.FAST_ONLY
        }
    }
