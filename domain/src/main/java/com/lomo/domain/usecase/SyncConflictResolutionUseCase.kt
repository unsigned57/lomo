package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncFailureException
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.WebDavSyncFailureException
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.WebDavSyncRepository

class SyncConflictResolutionUseCase(
    private val gitSyncRepository: GitSyncRepository,
    private val webDavSyncRepository: WebDavSyncRepository,
    private val s3SyncRepository: S3SyncRepository,
    private val memoRepository: MemoRepository,
) {
    suspend fun resolve(
        conflictSet: SyncConflictSet,
        resolution: SyncConflictResolution,
    ): SyncConflictResolutionResult {
        val result =
            when (conflictSet.source) {
            SyncBackendType.GIT ->
                gitSyncRepository
                    .resolveConflicts(resolution, conflictSet)
                    .toResolutionResult()

            SyncBackendType.WEBDAV ->
                webDavSyncRepository
                    .resolveConflicts(resolution, conflictSet)
                    .toResolutionResult()

            SyncBackendType.S3 ->
                s3SyncRepository
                    .resolveConflicts(resolution, conflictSet)
                    .toResolutionResult()

            SyncBackendType.NONE -> SyncConflictResolutionResult.Resolved
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

private fun com.lomo.domain.model.GitSyncResult.toResolutionResult(): SyncConflictResolutionResult =
    when (this) {
        is com.lomo.domain.model.GitSyncResult.Error ->
            throw GitSyncFailureException(
                code = code,
                message = message,
                cause = exception,
            )
        is com.lomo.domain.model.GitSyncResult.Conflict ->
            SyncConflictResolutionResult.Pending(conflicts)
        else -> SyncConflictResolutionResult.Resolved
    }

private fun com.lomo.domain.model.WebDavSyncResult.toResolutionResult(): SyncConflictResolutionResult =
    when (this) {
        is com.lomo.domain.model.WebDavSyncResult.Error ->
            throw WebDavSyncFailureException(
                code = code,
                message = message,
                cause = exception,
            )
        is com.lomo.domain.model.WebDavSyncResult.Conflict ->
            SyncConflictResolutionResult.Pending(conflicts)
        else -> SyncConflictResolutionResult.Resolved
    }

private fun com.lomo.domain.model.S3SyncResult.toResolutionResult(): SyncConflictResolutionResult =
    when (this) {
        is com.lomo.domain.model.S3SyncResult.Error ->
            throw S3SyncFailureException(
                code = code,
                message = message,
                cause = exception,
            )
        is com.lomo.domain.model.S3SyncResult.Conflict ->
            SyncConflictResolutionResult.Pending(conflicts)
        else -> SyncConflictResolutionResult.Resolved
    }
