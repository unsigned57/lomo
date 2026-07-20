package com.lomo.domain.repository

import kotlinx.coroutines.flow.StateFlow

interface ReminderCoordinator {
    val globalIntervalMillis: StateFlow<Long>

    suspend fun setGlobalIntervalMillis(millis: Long)

    suspend fun syncForMemo(memoId: String)

    suspend fun cancelForMemo(memoId: String)

    suspend fun rebuildAll()

    suspend fun snooze(
        memoId: String,
        reminderId: String,
    )

    suspend fun markDone(
        memoId: String,
        reminderId: String,
    )

    suspend fun recordFired(
        memoId: String,
        reminderId: String,
    )
}
