package com.lomo.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.repository.GitSyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class GitSyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val gitSyncRepository: GitSyncRepository,
    ) : CoroutineWorker(appContext, workerParams) {

        override suspend fun doWork(): Result {
            Timber.d("GitSyncWorker started")
            return when (val result = gitSyncRepository.sync()) {
                is GitSyncResult.Success -> {
                    Timber.d("GitSyncWorker success: ${result.message}")
                    Result.success()
                }
                is GitSyncResult.Error -> {
                    Timber.e("GitSyncWorker error: ${result.message}")
                    if (runAttemptCount < 3) Result.retry() else Result.failure()
                }
                // NotConfigured or DirectPathRequired — nothing to do
                else -> {
                    Timber.d("GitSyncWorker skipped: $result")
                    Result.success()
                }
            }
        }

        companion object {
            const val WORK_NAME = "com.lomo.data.worker.GitSyncWorker"
        }
    }
