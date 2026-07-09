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

    override suspend fun syncForMemo(memoId: String, content: String) = Unit

    override suspend fun cancelForMemo(memoId: String) = Unit

    override suspend fun rebuildAll() = Unit

    override suspend fun snooze(memoId: String, tokenRaw: String) = Unit

    override suspend fun markDone(memoId: String, tokenRaw: String) {
        lastMarkedDoneMemoId = memoId
        lastMarkedDoneTokenRaw = tokenRaw
        markDoneCalledCount++
    }

    override suspend fun recordFired(memoId: String, tokenRaw: String) = Unit
}
