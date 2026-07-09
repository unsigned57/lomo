package com.lomo.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncRefreshCoalescer(
    private val signalWindowMs: Long = DEFAULT_REFRESH_SIGNAL_WINDOW_MS,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var refreshLoopActive = false
    private var pendingRefreshSignal: SyncRefreshSignal? = null
    private var lastRefreshRequestAt: Long? = null

    suspend fun beginRefreshRequest(): SyncRefreshSignal? =
        mutex.withLock {
            val signal = resolveRefreshSignalLocked(nowProvider())
            if (refreshLoopActive) {
                pendingRefreshSignal = mergeRefreshSignals(pendingRefreshSignal, signal)
                null
            } else {
                refreshLoopActive = true
                signal
            }
        }

    suspend fun consumePendingRefreshSignal(): SyncRefreshSignal? =
        mutex.withLock {
            pendingRefreshSignal.also {
                pendingRefreshSignal = null
            }
        }

    suspend fun finishRefreshLoop() {
        mutex.withLock {
            refreshLoopActive = false
            pendingRefreshSignal = null
        }
    }

    private fun resolveRefreshSignalLocked(now: Long): SyncRefreshSignal {
        val previousRefreshAt = lastRefreshRequestAt
        lastRefreshRequestAt = now
        return if (previousRefreshAt != null && now - previousRefreshAt <= signalWindowMs) {
            SyncRefreshSignal.STRONG_REMOTE_HINT
        } else {
            SyncRefreshSignal.NORMAL
        }
    }
}

private fun mergeRefreshSignals(
    current: SyncRefreshSignal?,
    incoming: SyncRefreshSignal,
): SyncRefreshSignal =
    when {
        current == SyncRefreshSignal.STRONG_REMOTE_HINT || incoming == SyncRefreshSignal.STRONG_REMOTE_HINT ->
            SyncRefreshSignal.STRONG_REMOTE_HINT

        else -> incoming
    }

private const val DEFAULT_REFRESH_SIGNAL_WINDOW_MS = 1_500L
