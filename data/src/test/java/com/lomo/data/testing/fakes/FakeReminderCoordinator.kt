package com.lomo.data.testing.fakes

import com.lomo.domain.repository.ReminderCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Recording fake for [ReminderCoordinator]. Captures the (memoId, content) pairs passed to
 * [syncForMemo] and the number of [rebuildAll] reconciliations so tests can assert deterministic
 * scheduling without a real AlarmManager.
 */
class FakeReminderCoordinator : ReminderCoordinator {
    override val globalIntervalMillis: StateFlow<Long> = MutableStateFlow(60_000L)

    val syncForMemoCalls = mutableListOf<Pair<String, String>>()
    val cancelForMemoCalls = mutableListOf<String>()
    var rebuildAllCount = 0
        private set

    override suspend fun setGlobalIntervalMillis(millis: Long) = Unit

    override suspend fun syncForMemo(
        memoId: String,
        content: String,
    ) {
        syncForMemoCalls += memoId to content
    }

    override suspend fun cancelForMemo(memoId: String) {
        cancelForMemoCalls += memoId
    }

    override suspend fun rebuildAll() {
        rebuildAllCount++
    }

    override suspend fun snooze(
        memoId: String,
        tokenRaw: String,
    ) = Unit

    override suspend fun markDone(
        memoId: String,
        tokenRaw: String,
    ) = Unit

    override suspend fun recordFired(
        memoId: String,
        tokenRaw: String,
    ) = Unit
}
