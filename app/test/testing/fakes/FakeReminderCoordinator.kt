package com.lomo.app.testing.fakes

import com.lomo.domain.repository.ReminderCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeReminderCoordinator : ReminderCoordinator {
    override val globalIntervalMillis: StateFlow<Long> = MutableStateFlow(60000L)

    var lastMarkedDoneMemoId: String? = null
    var lastMarkedDoneTokenRaw: String? = null
    var markDoneCalledCount = 0

    override suspend fun setGlobalIntervalMillis(millis: Long) = Unit

    override suspend fun syncForMemo(memoId: String) = Unit

    override suspend fun cancelForMemo(memoId: String) = Unit

    override suspend fun rebuildAll() = Unit

    override suspend fun snooze(
        memoId: String,
        reminderId: String,
    ) = Unit

    override suspend fun markDone(
        memoId: String,
        reminderId: String,
    ) {
        lastMarkedDoneMemoId = memoId
        lastMarkedDoneTokenRaw = reminderId
        markDoneCalledCount++
    }

    override suspend fun recordFired(
        memoId: String,
        reminderId: String,
    ) = Unit
}
