package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.repository.MemoRepository

class SyncConflictResolutionUseCase(
    private val syncProviderRegistry: SyncProviderRegistry,
    private val memoRepository: MemoRepository,
) {
    suspend fun resolve(
        conflictSet: SyncConflictSet,
        resolution: SyncConflictResolution,
    ): SyncConflictResolutionResult {
        val result =
            when (conflictSet.source) {
                SyncBackendType.NONE -> SyncConflictResolutionResult.Resolved
                else ->
                    syncProviderRegistry
                        .get(conflictSet.source)
                        ?.resolveConflicts(resolution, conflictSet)
                        ?.toResolutionResult()
                        ?: SyncConflictResolutionResult.Resolved
            }
        if (result is SyncConflictResolutionResult.Pending) {
            return result
        }
        memoRepository.refreshMemos()
        return SyncConflictResolutionResult.Resolved
    }
}

sealed interface SyncConflictResolutionResult {
    data object Resolved : SyncConflictResolutionResult

    data class Pending(
        val conflictSet: SyncConflictSet,
    ) : SyncConflictResolutionResult
}
