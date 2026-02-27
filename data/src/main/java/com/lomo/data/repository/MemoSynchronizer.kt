package com.lomo.data.repository

import com.lomo.domain.model.Memo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

class MemoSynchronizer
    @Inject
    constructor(
        private val refreshEngine: MemoRefreshEngine,
        private val mutationHandler: MemoMutationHandler,
    ) {
        private val mutex = Mutex()
        private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val outboxDrainSignal = Channel<Unit>(Channel.CONFLATED)

        private val _outboxDrainCompleted = MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val outboxDrainCompleted: SharedFlow<Unit> = _outboxDrainCompleted.asSharedFlow()

        // Sync state for UI observation - helps prevent writes during active sync
        private val _isSyncing = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isSyncing: kotlinx.coroutines.flow.StateFlow<Boolean> = _isSyncing

        init {
            flushScope.launch {
                requestOutboxDrain()
                for (signal in outboxDrainSignal) {
                    if (signal != Unit) continue
                    try {
                        mutex.withLock {
                            drainOutboxLocked()
                        }
                        _outboxDrainCompleted.tryEmit(Unit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Background memo outbox drain failed")
                    }
                }
            }
        }

        suspend fun refresh(targetFilename: String? = null) =
            mutex.withLock {
                _isSyncing.value = true
                try {
                    drainOutboxLocked()
                    if (mutationHandler.hasPendingMemoFileOutbox()) {
                        Timber.w("Skip refresh because memo outbox is still pending")
                        return@withLock
                    }
                    refreshEngine.refresh(targetFilename)
                } finally {
                    _isSyncing.value = false
                }
            }

        suspend fun saveMemo(
            content: String,
            timestamp: Long,
        ) = mutex.withLock { withContext(Dispatchers.IO) { mutationHandler.saveMemo(content, timestamp) } }

        suspend fun saveMemoAsync(
            content: String,
            timestamp: Long,
        ) = withContext(Dispatchers.IO) {
            mutex.withLock {
                mutationHandler.saveMemoInDb(content, timestamp)
            }
            requestOutboxDrain()
        }

        suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ) = mutex.withLock { withContext(Dispatchers.IO) { mutationHandler.updateMemo(memo, newContent) } }

        suspend fun updateMemoAsync(
            memo: Memo,
            newContent: String,
        ) = withContext(Dispatchers.IO) {
            val outboxId =
                mutex.withLock {
                    mutationHandler.updateMemoInDb(memo, newContent)
                }
            if (outboxId != null) {
                requestOutboxDrain()
            }
        }

        suspend fun deleteMemo(memo: Memo) = mutex.withLock { withContext(Dispatchers.IO) { mutationHandler.deleteMemo(memo) } }

        suspend fun deleteMemoAsync(memo: Memo) =
            withContext(Dispatchers.IO) {
                val outboxId =
                    mutex.withLock {
                        mutationHandler.deleteMemoInDb(memo)
                    }
                if (outboxId != null) {
                    requestOutboxDrain()
                }
            }

        suspend fun restoreMemo(memo: Memo) = mutex.withLock { withContext(Dispatchers.IO) { mutationHandler.restoreMemo(memo) } }

        suspend fun restoreMemoAsync(memo: Memo) =
            withContext(Dispatchers.IO) {
                val outboxId =
                    mutex.withLock {
                        mutationHandler.restoreMemoInDb(memo)
                    }
                if (outboxId != null) {
                    requestOutboxDrain()
                }
            }

        suspend fun deletePermanently(memo: Memo) =
            mutex.withLock { withContext(Dispatchers.IO) { mutationHandler.deletePermanently(memo) } }

        private suspend fun drainOutboxLocked() {
            while (true) {
                val item = mutationHandler.nextMemoFileOutbox() ?: return
                try {
                    val flushed = mutationHandler.flushMemoFileOutbox(item)
                    if (flushed) {
                        mutationHandler.acknowledgeMemoFileOutbox(item.id)
                    } else {
                        mutationHandler.markMemoFileOutboxFailed(
                            id = item.id,
                            throwable = IllegalStateException("Outbox file flush returned false for ${item.operation}"),
                        )
                        return
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutationHandler.markMemoFileOutboxFailed(item.id, e)
                    return
                }
            }
        }

        private fun requestOutboxDrain() {
            outboxDrainSignal.trySend(Unit)
        }
    }
