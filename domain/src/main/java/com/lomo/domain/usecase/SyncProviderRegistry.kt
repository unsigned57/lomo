package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.UnifiedSyncProvider

class SyncProviderRegistry(
    providers: Set<UnifiedSyncProvider>,
) {
    private val providersByType = providers.toProviderMap()

    fun get(backendType: SyncBackendType): UnifiedSyncProvider? = providersByType[backendType]

    fun active(backendType: SyncBackendType): UnifiedSyncProvider? =
        get(backendType).takeIf { backendType != SyncBackendType.NONE }

    private fun Set<UnifiedSyncProvider>.toProviderMap(): Map<SyncBackendType, UnifiedSyncProvider> {
        val duplicateBackendTypes =
            groupingBy(UnifiedSyncProvider::backendType)
                .eachCount()
                .filterValues { count -> count > 1 }
                .keys

        require(duplicateBackendTypes.isEmpty()) {
            "Duplicate unified sync providers for backends: ${duplicateBackendTypes.joinToString()}"
        }

        return associateBy(UnifiedSyncProvider::backendType)
    }
}
