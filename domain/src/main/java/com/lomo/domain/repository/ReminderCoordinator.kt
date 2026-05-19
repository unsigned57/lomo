package com.lomo.domain.repository

import kotlinx.coroutines.flow.StateFlow

interface ReminderCoordinator {
    val globalIntervalMillis: StateFlow<Long>

    suspend fun setGlobalIntervalMillis(millis: Long)

    suspend fun syncForMemo(
        memoId: String,
        content: String,
    )

    suspend fun cancelForMemo(memoId: String)

    suspend fun rebuildAll()

    suspend fun snooze(
        memoId: String,
        tokenRaw: String,
    )

    suspend fun markDone(
        memoId: String,
        tokenRaw: String,
    )

    suspend fun recordFired(
        memoId: String,
        tokenRaw: String,
    )
}
