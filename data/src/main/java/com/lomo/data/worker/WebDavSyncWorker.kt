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
            Timber.d("%s started", WORKER_NAME)
            return when (val result = webDavSyncRepository.sync()) {
                is WebDavSyncResult.Success -> successWorkResult(WORKER_NAME, result.message)
                is WebDavSyncResult.Error -> errorWorkResult(WORKER_NAME, result.message)
                is WebDavSyncResult.Conflict -> conflictWorkResult(WORKER_NAME, result.message)
                WebDavSyncResult.NotConfigured -> skipWorkResult(WORKER_NAME, result)
            }
        }

        companion object {
            private const val WORKER_NAME = "WebDavSyncWorker"
            const val WORK_NAME = "com.lomo.data.worker.WebDavSyncWorker"
        }
    }
