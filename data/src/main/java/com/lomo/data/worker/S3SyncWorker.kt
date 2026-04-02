package com.lomo.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lomo.domain.model.S3SyncResult
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
            Timber.d("S3SyncWorker started")
            return when (val result = s3SyncRepository.sync()) {
                is S3SyncResult.Success -> {
                    Timber.d("S3SyncWorker success: ${result.message}")
                    Result.success()
                }

                is S3SyncResult.Error -> {
                    Timber.e("S3SyncWorker error: ${result.message}")
                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
                }

                is S3SyncResult.Conflict -> {
                    Timber.w("S3SyncWorker conflict detected: ${result.message}")
                    Result.success()
                }

                S3SyncResult.NotConfigured -> Result.success()
            }
        }

        companion object {
            private const val MAX_RETRY_ATTEMPTS = 3
            const val WORK_NAME = "com.lomo.data.worker.S3SyncWorker"
        }
    }
