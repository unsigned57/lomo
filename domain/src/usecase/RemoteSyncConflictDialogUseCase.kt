package com.lomo.domain.usecase

import com.lomo.domain.model.RemoteSyncBackendLabel
import com.lomo.domain.model.RemoteSyncConflictPage
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictPathStatus
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictAutoResolutionAdvisor
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.RemoteSyncCenterRepository

/**
 * Presentation bridge: original conflict dialog models ↔ Rust [RemoteSyncCenterRepository].
 *
 * Sole remote-conflict authority for host UI resolve (expected-revision fence). Does not call
 * deleted Kotlin Git/S3/WebDAV engines. Sync Inbox review stays on [SyncReviewResolutionUseCase].
 */
class RemoteSyncConflictDialogUseCase(
    private val remoteSyncCenterRepository: RemoteSyncCenterRepository,
    private val memoRepository: MemoMutationRepository,
    private val pageLimit: Int = DEFAULT_PAGE_LIMIT,
) {
    data class OpenSession(
        val workspaceRoot: String,
        val conflictSet: SyncConflictSet,
        val conflictRevision: Long,
        val sessionId: String,
    )

    /**
     * Load open remote conflicts for [workspaceRoot] into dialog-shaped [SyncConflictSet].
     *
     * Returns null when the workspace is blank or there are no open conflict paths.
     * Pages are walked until [nextCursor] is exhausted so the dialog sees the full open set
     * (large sets remain a known residual vs Sync Center pagination).
     */
    fun loadOpenSession(workspaceRoot: String): OpenSession? {
        val root = workspaceRoot.trim()
        if (root.isEmpty()) {
            return null
        }
        val backend = remoteSyncCenterRepository.configSummary(root).backend.toSyncBackendType()
        val pageWalk = walkOpenConflictPages(root)
        if (pageWalk.openPaths.isEmpty()) {
            return null
        }
        val files =
            pageWalk.openPaths.map { path ->
                path.toSyncConflictFile(workspaceRoot = root, repository = remoteSyncCenterRepository)
            }
        return OpenSession(
            workspaceRoot = root,
            conflictSet =
                SyncConflictSet(
                    source = backend,
                    files = files,
                    timestamp = System.currentTimeMillis(),
                ),
            conflictRevision = pageWalk.conflictRevision,
            sessionId = pageWalk.sessionId,
        )
    }

    private data class OpenPageWalk(
        val openPaths: List<RemoteSyncConflictPath>,
        val conflictRevision: Long,
        val sessionId: String,
    )

    private fun walkOpenConflictPages(root: String): OpenPageWalk {
        val openPaths = ArrayList<RemoteSyncConflictPath>()
        var cursor = 0
        var conflictRevision = 0L
        var sessionId = ""
        var hasMore = true
        while (hasMore) {
            val page: RemoteSyncConflictPage =
                remoteSyncCenterRepository.listConflicts(
                    workspaceRoot = root,
                    cursor = cursor,
                    limit = pageLimit,
                )
            conflictRevision = page.conflictRevision
            sessionId = page.sessionId
            page.items
                .asSequence()
                .filter { path -> path.status == RemoteSyncConflictPathStatus.Open }
                .forEach(openPaths::add)
            val next = page.nextCursor
            hasMore = next != null && next > cursor
            if (hasMore) {
                cursor = checkNotNull(next)
            }
        }
        return OpenPageWalk(
            openPaths = openPaths,
            conflictRevision = conflictRevision,
            sessionId = sessionId,
        )
    }

    sealed interface DialogResolveResult {
        data object Resolved : DialogResolveResult

        data class Pending(
            val session: OpenSession,
        ) : DialogResolveResult
    }

    /**
     * Apply dialog choices through Rust expected-revision resolve, then re-list open paths.
     *
     * Pending returns remaining open files only (SkipForNow is durable Rust status, not re-shown
     * as Open). Empty open set after apply → Resolved (+ memo refresh in [resolveSuspending]).
     */
    fun resolve(
        session: OpenSession,
        resolution: SyncConflictResolution,
    ): DialogResolveResult {
        val resolutions =
            session.conflictSet.files.mapNotNull { file ->
                val choice = resolution.perFileChoices[file.relativePath] ?: return@mapNotNull null
                choice.toRemoteResolution(file)
            }
        if (resolutions.isEmpty()) {
            return DialogResolveResult.Pending(session)
        }
        remoteSyncCenterRepository.resolveConflicts(
            workspaceRoot = session.workspaceRoot,
            expectedRevision = session.conflictRevision,
            resolutions = resolutions,
        )
        val remaining = loadOpenSession(session.workspaceRoot)
        return if (remaining == null || remaining.conflictSet.files.isEmpty()) {
            DialogResolveResult.Resolved
        } else {
            DialogResolveResult.Pending(remaining)
        }
    }

    suspend fun resolveSuspending(
        session: OpenSession,
        resolution: SyncConflictResolution,
    ): DialogResolveResult {
        val result = resolve(session, resolution)
        if (result is DialogResolveResult.Resolved) {
            memoRepository.refreshMemos()
        }
        return result
    }

    companion object {
        const val DEFAULT_PAGE_LIMIT: Int = 100
    }
}

internal fun RemoteSyncBackendLabel.toSyncBackendType(): SyncBackendType =
    when (this) {
        RemoteSyncBackendLabel.None -> SyncBackendType.NONE
        RemoteSyncBackendLabel.Git -> SyncBackendType.GIT
        RemoteSyncBackendLabel.WebDav -> SyncBackendType.WEBDAV
        RemoteSyncBackendLabel.S3 -> SyncBackendType.S3
    }

internal fun RemoteSyncConflictPath.toSyncConflictFile(
    workspaceRoot: String,
    repository: RemoteSyncCenterRepository,
): SyncConflictFile {
    if (isBinary) {
        return SyncConflictFile(
            relativePath = path,
            localContent = null,
            remoteContent = null,
            isBinary = true,
        )
    }
    val facts =
        repository.markdownConflictFacts(
            workspaceRoot = workspaceRoot,
            path = this,
            mergedDraft = null,
        )
    return SyncConflictFile(
        relativePath = path,
        localContent = facts.localBody,
        remoteContent = facts.remoteBody,
        isBinary = false,
    )
}

internal fun SyncConflictResolutionChoice.toRemoteResolution(
    file: SyncConflictFile,
): RemoteSyncConflictResolution =
    when (this) {
        SyncConflictResolutionChoice.KEEP_LOCAL ->
            RemoteSyncConflictResolution(
                path = file.relativePath,
                kind = RemoteSyncConflictResolution.KIND_KEEP_LOCAL,
            )

        SyncConflictResolutionChoice.KEEP_REMOTE ->
            RemoteSyncConflictResolution(
                path = file.relativePath,
                kind = RemoteSyncConflictResolution.KIND_KEEP_REMOTE,
            )

        SyncConflictResolutionChoice.SKIP_FOR_NOW ->
            RemoteSyncConflictResolution(
                path = file.relativePath,
                kind = RemoteSyncConflictResolution.KIND_SKIP_FOR_NOW,
            )

        SyncConflictResolutionChoice.MERGE_TEXT -> {
            val merged =
                SyncConflictAutoResolutionAdvisor.mergedText(file)
                    ?: error("MERGE_TEXT requires mergeable markdown bodies for ${file.relativePath}")
            RemoteSyncConflictResolution(
                path = file.relativePath,
                kind = RemoteSyncConflictResolution.KIND_MERGED_BODY,
                mergedBody = merged,
            )
        }
    }
