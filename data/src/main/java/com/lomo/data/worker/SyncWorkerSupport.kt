package com.lomo.data.worker

import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import timber.log.Timber

internal const val DEFAULT_SYNC_WORKER_MAX_RETRY_ATTEMPTS = 3

internal fun CoroutineWorker.successWorkResult(
    workerName: String,
    detail: String? = null,
): ListenableWorker.Result {
    if (detail == null) {
        Timber.d("%s success", workerName)
    } else {
        Timber.d("%s success: %s", workerName, detail)
    }
    return ListenableWorker.Result.success()
}

internal fun CoroutineWorker.errorWorkResult(
    workerName: String,
    message: String,
    throwable: Throwable? = null,
    maxRetryAttempts: Int = DEFAULT_SYNC_WORKER_MAX_RETRY_ATTEMPTS,
): ListenableWorker.Result {
    if (throwable == null) {
        Timber.e("%s error: %s", workerName, message)
    } else {
        Timber.e(throwable, "%s error: %s", workerName, message)
    }
    return retryOrFailure(maxRetryAttempts)
}

internal fun CoroutineWorker.conflictWorkResult(
    workerName: String,
    message: String,
): ListenableWorker.Result {
    Timber.w("%s conflict detected: %s", workerName, message)
    return ListenableWorker.Result.success()
}

internal fun skipWorkResult(
    workerName: String,
    detail: Any? = null,
): ListenableWorker.Result {
    if (detail == null) {
        Timber.d("%s skipped", workerName)
    } else {
        Timber.d("%s skipped: %s", workerName, detail)
    }
    return ListenableWorker.Result.success()
}

internal fun CoroutineWorker.retryOrFailure(
    maxRetryAttempts: Int = DEFAULT_SYNC_WORKER_MAX_RETRY_ATTEMPTS,
): ListenableWorker.Result =
    if (runAttemptCount < maxRetryAttempts) {
        ListenableWorker.Result.retry()
    } else {
        ListenableWorker.Result.failure()
    }
