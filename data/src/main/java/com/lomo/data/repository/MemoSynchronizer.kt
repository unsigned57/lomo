package com.lomo.data.repository

import com.lomo.domain.model.Memo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

class MemoSynchronizer
    @Inject
    constructor(
        private val refreshEngine: MemoRefreshEngine,
        private val mutationHandler: MemoMutationHandler,
    ) {
        private val mutex = Mutex()

        // Sync state for UI observation - helps prevent writes during active sync
        private val _isSyncing = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isSyncing: kotlinx.coroutines.flow.StateFlow<Boolean> = _isSyncing

        suspend fun refresh(targetFilename: String? = null) =
            mutex.withLock {
                _isSyncing.value = true
                try {
                    refreshEngine.refresh(targetFilename)
                } finally {
                    _isSyncing.value = false
                }
            }

        suspend fun saveMemo(
            content: String,
            timestamp: Long,
        ) = mutex.withLock { withContext(Dispatchers.IO) { mutationHandler.saveMemo(content, timestamp) } }

        suspend fun prewarmTodayMemoTarget(timestamp: Long) =
            mutex.withLock { withContext(Dispatchers.IO) { mutationHandler.prewarmTodayMemoTarget(timestamp) } }

        suspend fun cleanupTodayPrewarmedMemoTarget(timestamp: Long) =
            mutex.withLock { withContext(Dispatchers.IO) { mutationHandler.cleanupTodayPrewarmedMemoTarget(timestamp) } }

        suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ) = mutex.withLock { withContext(Dispatchers.IO) { mutationHandler.updateMemo(memo, newContent) } }

        suspend fun deleteMemo(memo: Memo) = mutex.withLock { withContext(Dispatchers.IO) { mutationHandler.deleteMemo(memo) } }

        suspend fun restoreMemo(memo: Memo) = mutex.withLock { withContext(Dispatchers.IO) { mutationHandler.restoreMemo(memo) } }

        suspend fun deletePermanently(memo: Memo) =
            mutex.withLock { withContext(Dispatchers.IO) { mutationHandler.deletePermanently(memo) } }
    }
