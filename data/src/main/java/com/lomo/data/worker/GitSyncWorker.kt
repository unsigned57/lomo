package com.lomo.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.repository.GitSyncRepository
import timber.log.Timber

class GitSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val gitSyncRepository: GitSyncRepository,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Timber.d("%s started", WORKER_NAME)
        return when (val result = gitSyncRepository.sync()) {
            is GitSyncResult.Success -> successWorkResult(WORKER_NAME, result.message)
            is GitSyncResult.Error -> errorWorkResult(WORKER_NAME, result.message)

            // NotConfigured or DirectPathRequired — nothing to do
            else -> skipWorkResult(WORKER_NAME, result)
        }
    }

    companion object {
        private const val WORKER_NAME = "GitSyncWorker"
        const val WORK_NAME = "com.lomo.data.worker.GitSyncWorker"
    }
}
