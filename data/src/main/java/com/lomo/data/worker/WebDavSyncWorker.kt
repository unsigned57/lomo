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
                    if (runAttemptCount < 3) Result.retry() else Result.failure()
                }

                WebDavSyncResult.NotConfigured -> {
                    Result.success()
                }
            }
        }

        companion object {
            const val WORK_NAME = "com.lomo.data.worker.WebDavSyncWorker"
        }
    }
