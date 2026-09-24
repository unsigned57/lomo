package com.lomo.app.testing.fakes

import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stateful memo-snapshot preferences for settings tests. */
class FakeMemoSnapshotPreferencesRepository(
    initialSnapshotsEnabled: Boolean = PreferenceDefaults.MEMO_SNAPSHOTS_ENABLED,
) : MemoSnapshotPreferencesRepository {
    val snapshotsEnabled = MutableStateFlow(initialSnapshotsEnabled)
    val maxCount = MutableStateFlow(PreferenceDefaults.MEMO_SNAPSHOT_MAX_COUNT)
    val maxAgeDays = MutableStateFlow(PreferenceDefaults.MEMO_SNAPSHOT_MAX_AGE_DAYS)

    override fun isMemoSnapshotsEnabled(): Flow<Boolean> = snapshotsEnabled.asStateFlow()

    override suspend fun setMemoSnapshotsEnabled(enabled: Boolean) {
        snapshotsEnabled.value = enabled
    }

    override fun getMemoSnapshotMaxCount(): Flow<Int> = maxCount.asStateFlow()

    override suspend fun setMemoSnapshotMaxCount(count: Int) {
        maxCount.value = count
    }

    override fun getMemoSnapshotMaxAgeDays(): Flow<Int> = maxAgeDays.asStateFlow()

    override suspend fun setMemoSnapshotMaxAgeDays(days: Int) {
        maxAgeDays.value = days
    }
}
