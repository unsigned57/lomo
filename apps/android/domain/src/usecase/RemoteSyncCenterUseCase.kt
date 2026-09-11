package com.lomo.domain.usecase

import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPage
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncConflictResolveResult
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts
import com.lomo.domain.model.RemoteSyncSessionProgress
import com.lomo.domain.repository.RemoteSyncCenterRepository

/**
 * App-facing port for Sync Center presentation over [RemoteSyncCenterRepository].
 *
 * Keeps ViewModels free of domain.repository types while preserving expected-revision resolve
 * and markdown/binary detail honesty.
 */
class RemoteSyncCenterUseCase(
    private val repository: RemoteSyncCenterRepository,
) {
    fun configSummary(workspaceRoot: String): RemoteSyncConfigSummary =
        repository.configSummary(workspaceRoot)

    fun sessionProgress(workspaceRoot: String): RemoteSyncSessionProgress =
        repository.sessionProgress(workspaceRoot)

    fun listConflicts(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ): RemoteSyncConflictPage =
        repository.listConflicts(
            workspaceRoot = workspaceRoot,
            cursor = cursor,
            limit = limit,
        )

    fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult =
        repository.resolveConflicts(
            workspaceRoot = workspaceRoot,
            expectedRevision = expectedRevision,
            resolutions = resolutions,
        )

    fun markdownConflictFacts(
        workspaceRoot: String,
        path: RemoteSyncConflictPath,
        mergedDraft: String?,
    ): RemoteSyncMarkdownConflictFacts =
        repository.markdownConflictFacts(
            workspaceRoot = workspaceRoot,
            path = path,
            mergedDraft = mergedDraft,
        )

    fun binaryConflictFacts(
        workspaceRoot: String,
        path: RemoteSyncConflictPath,
    ): RemoteSyncBinaryConflictFacts =
        repository.binaryConflictFacts(
            workspaceRoot = workspaceRoot,
            path = path,
        )
}
