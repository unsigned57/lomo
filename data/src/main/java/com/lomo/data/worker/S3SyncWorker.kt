package com.lomo.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.lomo.data.repository.S3_SYNC_WORK_INTENT_PARAMETER
import com.lomo.data.repository.S3SyncWorkExecutor
import com.lomo.data.repository.S3SyncWorkIntent
import com.lomo.domain.model.S3SyncResult
import timber.log.Timber

class S3SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val s3SyncExecutor: S3SyncWorkExecutor,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Timber.d("%s started", WORKER_NAME)
        val intent = resolveWorkIntent(inputData)
        return when (val result = s3SyncExecutor.executeS3Sync(intent)) {
            is S3SyncResult.Success -> successWorkResult(WORKER_NAME, result.message)
            is S3SyncResult.Error -> errorWorkResult(WORKER_NAME, result.message)
            is S3SyncResult.Conflict -> conflictWorkResult(WORKER_NAME, result.message)
            is S3SyncResult.Review -> conflictWorkResult(WORKER_NAME, result.message)
            S3SyncResult.NotConfigured -> skipWorkResult(WORKER_NAME, result)
        }
    }

    companion object {
        private const val WORKER_NAME = "S3SyncWorker"
        const val WORK_NAME = "com.lomo.data.worker.S3SyncWorker"
        const val RECONCILE_WORK_NAME = "com.lomo.data.worker.S3SyncWorker.RECONCILE"
        const val RECONCILE_CATCH_UP_WORK_NAME = "com.lomo.data.worker.S3SyncWorker.RECONCILE_CATCH_UP"
        internal fun inputData(intent: S3SyncWorkIntent): Data =
            Data
                .Builder()
                .putString(S3_SYNC_WORK_INTENT_PARAMETER, intent.name)
                .build()

        internal fun resolveWorkIntent(inputData: Data): S3SyncWorkIntent =
            inputData.getString(S3_SYNC_WORK_INTENT_PARAMETER)
                ?.let { raw ->
                    enumValues<S3SyncWorkIntent>().firstOrNull { candidate -> candidate.name == raw }
                } ?: S3SyncWorkIntent.FAST_ONLY
    }
}
