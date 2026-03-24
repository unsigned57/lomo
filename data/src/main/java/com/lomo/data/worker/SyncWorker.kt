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
            Timber.d("SyncWorker started")
            return runNonFatalCatching {
                memoSynchronizer.refresh()
                Timber.d("SyncWorker success")
                Result.success()
            }.getOrElse { error ->
                Timber.e(error, "SyncWorker failed")
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        }

        companion object {
            private const val MAX_RETRY_ATTEMPTS = 3
            const val WORK_NAME = "com.lomo.data.worker.SyncWorker"
        }
    }
