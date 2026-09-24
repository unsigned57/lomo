package com.lomo.data.testing.fakes

import com.lomo.domain.repository.SyncInboxPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSyncInboxPreferencesRepository : SyncInboxPreferencesRepository {
    private val syncInboxEnabled = MutableStateFlow(true)

    fun setSyncInboxPreferenceEnabled(enabled: Boolean) {
        syncInboxEnabled.value = enabled
    }

    override fun isSyncInboxEnabled(): Flow<Boolean> = syncInboxEnabled.asStateFlow()

    override suspend fun setSyncInboxEnabled(enabled: Boolean) {
        syncInboxEnabled.value = enabled
    }
}
