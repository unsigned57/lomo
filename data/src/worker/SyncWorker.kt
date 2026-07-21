package com.lomo.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.repository.MemoMutationRepository
import timber.log.Timber

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val memoMutationRepository: MemoMutationRepository,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Timber.d("%s started", WORKER_NAME)
        return runNonFatalCatching<Result> {
            memoMutationRepository.refreshMemos()
            successWorkResult(WORKER_NAME)
        }.getOrElse { error ->
            errorWorkResult(
                workerName = WORKER_NAME,
                message = "memo refresh failed",
                throwable = error,
            )
        }
    }

    companion object {
        private const val WORKER_NAME = "SyncWorker"
        const val WORK_NAME = "com.lomo.data.worker.SyncWorker"
    }
}
