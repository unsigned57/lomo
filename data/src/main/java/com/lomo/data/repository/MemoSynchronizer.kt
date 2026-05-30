package com.lomo.data.repository

import com.lomo.data.di.ApplicationScope
import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.Memo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private const val OUTBOX_COMPLETION_FAILURE_PREFIX = "completion:"

@Singleton
class MemoSynchronizer
    internal constructor(
        private val refreshEngine: MemoRefreshEngine,
        private val mutationHandler: MemoMutationHandler,
        outboxScope: CoroutineScope,
        private val startOutboxCoordinator: Boolean,
    ) {
        @Inject
        constructor(
            refreshEngine: MemoRefreshEngine,
            mutationHandler: MemoMutationHandler,
            @ApplicationScope outboxScope: CoroutineScope,
        ) : this(refreshEngine, mutationHandler, outboxScope, true)

        private val mutex = Mutex()
        private val outboxCoordinator = MemoOutboxDrainCoordinator(mutationHandler, mutex, outboxScope)
        val outboxDrainCompleted: SharedFlow<Unit> = outboxCoordinator.outboxDrainCompleted

        // Sync state for UI observation - helps prevent writes during active sync
        private val _isSyncing = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isSyncing: kotlinx.coroutines.flow.StateFlow<Boolean> = _isSyncing

        init {
            if (startOutboxCoordinator) {
                outboxCoordinator.start()
            }
        }

        suspend fun refresh(targetFilename: String? = null) =
            mutex.withLock {
                _isSyncing.value = true
                try {
                    outboxCoordinator.drainOutboxLocked()
                    if (mutationHandler.hasPendingMemoFileOutbox()) {
                        Timber.w("Skip refresh because memo outbox is still pending")
                        return@withLock
                    }
                    refreshEngine.refresh(targetFilename)
                } finally {
                    _isSyncing.value = false
                }
            }

        suspend fun refreshImportedSync(targetFilename: String? = null) =
            mutex.withLock {
                _isSyncing.value = true
                try {
                    outboxCoordinator.drainOutboxLocked()
                    if (mutationHandler.hasPendingMemoFileOutbox()) {
                        Timber.w("Skip refresh because memo outbox is still pending")
                        return@withLock
                    }
                    refreshEngine.refreshImportedSync(targetFilename)
                } finally {
                    _isSyncing.value = false
                }
            }

        suspend fun saveMemo(
            content: String,
            timestamp: Long,
            geoLocation: String? = null,
        ): Memo = withContext(Dispatchers.IO) {
            val saveResult =
                mutex.withLock {
                    mutationHandler.saveMemoInDb(content, timestamp, geoLocation)
                }
            outboxCoordinator.requestOutboxDrain()
            saveResult.savePlan.memo
        }

        suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ) = withContext(Dispatchers.IO) {
            val outboxId =
                mutex.withLock {
                    mutationHandler.updateMemoInDb(memo, newContent)
                }
            if (outboxId != null) {
                outboxCoordinator.requestOutboxDrain()
            }
        }

        suspend fun deleteMemo(memo: Memo) =
            withContext(Dispatchers.IO) {
                val outboxId =
                    mutex.withLock {
                        mutationHandler.deleteMemoInDb(memo)
                    }
                if (outboxId != null) {
                    outboxCoordinator.requestOutboxDrain()
                }
            }

        suspend fun restoreMemo(memo: Memo) =
            withContext(Dispatchers.IO) {
                val outboxId =
                    mutex.withLock {
                        mutationHandler.restoreMemoInDb(memo)
                    }
                if (outboxId != null) {
                    outboxCoordinator.requestOutboxDrain()
                }
            }

        suspend fun restoreMemoRevision(
            currentMemo: Memo,
            revisionId: String,
        ) = withContext(Dispatchers.IO) {
            mutex.withLock {
                mutationHandler.restoreMemoRevisionInDb(
                    currentMemo = currentMemo,
                    revisionId = revisionId,
                )
            }
            outboxCoordinator.requestOutboxDrain()
        }

        suspend fun deletePermanently(memo: Memo) =
            withContext(Dispatchers.IO) {
                val outboxId =
                    mutex.withLock {
                        mutationHandler.deletePermanentlyInDb(memo)
                    }
                if (outboxId != null) {
                    outboxCoordinator.requestOutboxDrain()
                }
            }

        suspend fun clearTrash() =
            withContext(Dispatchers.IO) {
                val outboxCount =
                    mutex.withLock {
                        mutationHandler.clearTrash()
                    }
                if (outboxCount > 0) {
                    outboxCoordinator.requestOutboxDrain()
                }
            }
    }

private class MemoOutboxDrainCoordinator(
    private val mutationHandler: MemoMutationHandler,
    private val mutex: Mutex,
    private val flushScope: CoroutineScope,
) {
    private val outboxDrainSignal = Channel<Unit>(Channel.CONFLATED)
    private val _outboxDrainCompleted =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val outboxDrainCompleted: SharedFlow<Unit> = _outboxDrainCompleted.asSharedFlow()

    fun start() {
        flushScope.launch {
            requestOutboxDrain()
            for (signal in outboxDrainSignal) {
                if (signal != Unit) continue
                runNonFatalCatching {
                    mutex.withLock {
                        drainOutboxLocked()
                    }
                    _outboxDrainCompleted.tryEmit(Unit)
                }.onFailure { error ->
                    Timber.w(error, "Background memo outbox drain failed")
                }
            }
        }
    }

    suspend fun drainOutboxLocked() {
        var shouldContinue = true
        while (shouldContinue) {
            val item = mutationHandler.nextMemoFileOutbox() ?: break
            shouldContinue = processOutboxItem(item)
        }
    }

    fun requestOutboxDrain() {
        outboxDrainSignal.trySend(Unit)
    }

    private suspend fun handleOutboxFailure(
        item: MemoFileOutboxEntity,
        throwable: Throwable,
    ): Boolean {
        mutationHandler.markMemoFileOutboxFailed(item.id, throwable)
        val nextRetryCount = item.retryCount + 1
        if (nextRetryCount >= MAX_OUTBOX_RETRIES) {
            Timber.e(
                throwable,
                "Drop poisoned outbox item id=%d op=%s memoId=%s after %d retries",
                item.id,
                item.operation,
                item.memoId,
                nextRetryCount,
            )
            mutationHandler.acknowledgeMemoFileOutbox(item.id)
            return true
        }

        scheduleRetry(nextRetryCount)
        return false
    }

    private suspend fun processOutboxItem(item: MemoFileOutboxEntity): Boolean {
            if (item.retryCount >= MAX_OUTBOX_RETRIES) {
                if (isOutboxCompletionFailure(item.lastError)) {
                    Timber.e(
                        "Keep poisoned outbox item id=%d op=%s memoId=%s retryCount=%d " +
                            "because lifecycle completion has not finished",
                        item.id,
                    item.operation,
                    item.memoId,
                    item.retryCount,
                )
                return false
            }
            Timber.e(
                "Drop poisoned outbox item id=%d op=%s memoId=%s retryCount=%d",
                item.id,
                item.operation,
                item.memoId,
                item.retryCount,
            )
            mutationHandler.acknowledgeMemoFileOutbox(item.id)
            return true
        }

        return runNonFatalCatching {
            flushOrRetryOutboxItem(item)
        }.getOrElse { error ->
            handleOutboxCompletionFailure(item, error)
        }
    }

    private suspend fun handleOutboxCompletionFailure(
        item: MemoFileOutboxEntity,
        throwable: Throwable,
    ): Boolean {
        mutationHandler.markMemoFileOutboxFailed(item.id, throwable.toOutboxCompletionFailure())
        scheduleRetry(item.retryCount + 1)
        return false
    }

    private suspend fun flushOrRetryOutboxItem(item: MemoFileOutboxEntity): Boolean {
        val flushed = mutationHandler.flushMemoFileOutbox(item)
        return if (flushed) {
            mutationHandler.acknowledgeMemoFileOutbox(item.id)
            true
        } else if (item.operation == com.lomo.data.local.entity.MemoFileOutboxOp.PERMANENT_DELETE) {
            handleOutboxCompletionFailure(
                item = item,
                throwable =
                    MemoOutboxLifecycleCompletionException(
                        "PERMANENT_DELETE outbox completion did not finish for memo ${item.memoId}",
                    ),
            )
        } else {
            handleOutboxFailure(
                item = item,
                throwable = IllegalStateException("Outbox file flush returned false for ${item.operation}"),
            )
        }
    }

    private fun scheduleRetry(retryCount: Int) {
        val delayMillis = OUTBOX_RETRY_DELAY_MS * retryCount.coerceAtLeast(1)
        flushScope.launch {
            delay(delayMillis)
            requestOutboxDrain()
        }
    }

    private companion object {
        const val MAX_OUTBOX_RETRIES = 5
        const val OUTBOX_RETRY_DELAY_MS = 1_500L
    }
}

private fun Throwable.toOutboxCompletionFailure(): IllegalStateException =
    IllegalStateException(
        "$OUTBOX_COMPLETION_FAILURE_PREFIX ${message ?: javaClass.simpleName}",
        this,
    )

private fun isOutboxCompletionFailure(lastError: String?): Boolean =
    lastError?.startsWith(OUTBOX_COMPLETION_FAILURE_PREFIX) == true
