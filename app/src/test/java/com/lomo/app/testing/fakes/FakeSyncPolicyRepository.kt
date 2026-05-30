package com.lomo.app.testing.fakes

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.SyncPolicyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSyncPolicyRepository : SyncPolicyRepository {
    private val _remoteSyncBackend = MutableStateFlow(SyncBackendType.NONE)

    fun updateRemoteSyncBackend(backend: SyncBackendType) {
        _remoteSyncBackend.value = backend
    }

    override fun ensureCoreSyncActive() {}

    override fun observeRemoteSyncBackend(): Flow<SyncBackendType> = _remoteSyncBackend.asStateFlow()

    override suspend fun setRemoteSyncBackend(type: SyncBackendType) {
        _remoteSyncBackend.value = type
    }

    override suspend fun applyRemoteSyncPolicy() {}
}
