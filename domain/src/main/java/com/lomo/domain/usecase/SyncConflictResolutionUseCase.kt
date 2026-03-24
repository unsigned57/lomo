package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncFailureException
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.WebDavSyncFailureException
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.WebDavSyncRepository

class SyncConflictResolutionUseCase(
    private val gitSyncRepository: GitSyncRepository,
    private val webDavSyncRepository: WebDavSyncRepository,
    private val memoRepository: MemoRepository,
) {
    suspend fun resolve(
        conflictSet: SyncConflictSet,
        resolution: SyncConflictResolution,
    ) {
        when (conflictSet.source) {
            SyncBackendType.GIT -> {
                val result = gitSyncRepository.resolveConflicts(resolution, conflictSet)
                if (result is com.lomo.domain.model.GitSyncResult.Error) {
                    throw GitSyncFailureException(
                        code = result.code,
                        message = result.message,
                        cause = result.exception,
                    )
                }
            }
            SyncBackendType.WEBDAV -> {
                val result = webDavSyncRepository.resolveConflicts(resolution, conflictSet)
                if (result is com.lomo.domain.model.WebDavSyncResult.Error) {
                    throw WebDavSyncFailureException(
                        code = result.code,
                        message = result.message,
                        cause = result.exception,
                    )
                }
            }
            SyncBackendType.NONE -> {}
        }
        memoRepository.refreshMemos()
    }
}
