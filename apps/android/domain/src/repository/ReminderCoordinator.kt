package com.lomo.domain.repository

import com.lomo.domain.model.ReminderInterval
import kotlinx.coroutines.flow.StateFlow

interface ReminderCoordinator {
    /** Active snooze interval preference as an owner-vocabulary option. */
    val globalInterval: StateFlow<ReminderInterval>

    suspend fun setGlobalInterval(interval: ReminderInterval)

    suspend fun syncForMemo(memoId: String)

    suspend fun cancelForMemo(
        memoId: String,
        reminderIds: Set<String>,
    )

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
