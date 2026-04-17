package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.UnifiedSyncProvider

class SyncProviderRegistry(
    providers: List<UnifiedSyncProvider>,
) {
    private val providersByType = providers.associateBy(UnifiedSyncProvider::backendType)

    fun get(backendType: SyncBackendType): UnifiedSyncProvider? = providersByType[backendType]

    fun active(backendType: SyncBackendType): UnifiedSyncProvider? =
        get(backendType).takeIf { backendType != SyncBackendType.NONE }
}
