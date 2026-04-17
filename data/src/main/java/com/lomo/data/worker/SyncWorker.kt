package com.lomo.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lomo.data.util.runNonFatalCatching
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val memoSynchronizer: com.lomo.data.repository.MemoSynchronizer,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result {
            Timber.d("%s started", WORKER_NAME)
            return runNonFatalCatching<Result> {
                memoSynchronizer.refresh()
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
