package com.lomo.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.repository.WebDavSyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class WebDavSyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val webDavSyncRepository: WebDavSyncRepository,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result {
            Timber.d("WebDavSyncWorker started")
            return when (val result = webDavSyncRepository.sync()) {
                is WebDavSyncResult.Success -> {
                    Timber.d("WebDavSyncWorker success: ${result.message}")
                    Result.success()
                }

                is WebDavSyncResult.Error -> {
                    Timber.e("WebDavSyncWorker error: ${result.message}")
                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
                }

                is WebDavSyncResult.Conflict -> {
                    Timber.w("WebDavSyncWorker conflict detected: ${result.message}")
                    Result.success()
                }

                WebDavSyncResult.NotConfigured -> {
                    Result.success()
                }
            }
        }

        companion object {
            private const val MAX_RETRY_ATTEMPTS = 3
            const val WORK_NAME = "com.lomo.data.worker.WebDavSyncWorker"
        }
    }
