package com.lomo.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MemoMutationRepository
import timber.log.Timber

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val memoMutationRepository: MemoMutationRepository,
    private val engineReadiness: EngineReadinessRepository,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Timber.d("%s started", WORKER_NAME)
        when (val readiness = engineReadiness.requestEngineStart()) {
            EngineReadiness.Ready -> Unit

            EngineReadiness.Opening -> return retryOrFailure(MAX_START_ATTEMPTS)

            is EngineReadiness.ReadOnlyRecovery -> {
                return if (readiness.retryDisposition == EngineRetryDisposition.TRANSIENT) {
                    retryOrFailure(MAX_START_ATTEMPTS)
                } else {
                    skipWorkResult(WORKER_NAME, readiness.code)
                }
            }

            EngineReadiness.AwaitingWorkspaceSelection,
            EngineReadiness.ShuttingDown,
            -> return skipWorkResult(WORKER_NAME, readiness::class.simpleName)
        }
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
        private const val MAX_START_ATTEMPTS = 3
        private const val WORKER_NAME = "SyncWorker"
        const val WORK_NAME = "com.lomo.data.worker.SyncWorker"
    }
}
