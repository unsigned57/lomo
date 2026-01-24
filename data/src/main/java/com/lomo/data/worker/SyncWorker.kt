package com.lomo.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lomo.data.repository.MemoSynchronizer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val synchronizer: MemoSynchronizer,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result =
            try {
                synchronizer.refresh()
                Result.success()
            } catch (e: java.io.IOException) {
                // Network/IO errors - might be temporary, can retry
                e.printStackTrace()
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            } catch (e: Exception) {
                // Other exceptions - likely permanent, fail immediately
                e.printStackTrace()
                Result.failure()
            }

        companion object {
            const val WORK_NAME = "com.lomo.data.worker.SyncWorker"
        }
    }
