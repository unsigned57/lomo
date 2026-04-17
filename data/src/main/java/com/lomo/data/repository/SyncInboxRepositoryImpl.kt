package com.lomo.data.repository

import android.content.Context
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictAutoResolutionAdvisor
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSessionKind
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncConflictTextMerge
import com.lomo.domain.model.UnifiedSyncError
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.SyncInboxRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val INBOX_PREFIX = "inbox/"

@Singleton
class SyncInboxRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val preferencesRepository: PreferencesRepository,
        private val workspaceConfigSource: WorkspaceConfigSource,
        private val markdownStorageDataSource: MarkdownStorageDataSource,
        private val workspaceMediaAccess: WorkspaceMediaAccess,
        private val memoSynchronizer: MemoSynchronizer,
        private val pendingConflictStore: PendingSyncConflictStore,
    ) : SyncInboxRepository {
    private val state = MutableStateFlow<UnifiedSyncState>(UnifiedSyncState.Idle)

    override fun syncState(): Flow<UnifiedSyncState> = state

    override suspend fun ensureDirectoryStructure() {
        val inboxRoot = workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX).first() ?: return
        ensureInboxDirectoryStructure(
            context = context,
            inboxRoot = inboxRoot,
        )
    }

    override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult =
        when (operation) {
            UnifiedSyncOperation.MANUAL_SYNC,
            UnifiedSyncOperation.REFRESH_SYNC,
            UnifiedSyncOperation.PROCESS_PENDING_CHANGES,
            -> processPendingInbox()
        }

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): UnifiedSyncResult {
        state.value = UnifiedSyncState.Running(SyncBackendType.INBOX, UnifiedSyncPhase.INITIALIZING)
        val inboxRoot =
            workspaceConfigSource
                .getRootFlow(StorageRootType.SYNC_INBOX)
                .first()
                ?: return notConfiguredResult()

        val remaining = applyInboxConflictResolution(inboxRoot, conflictSet, resolution)
        return if (remaining.isEmpty()) {
            pendingConflictStore.clear(SyncBackendType.INBOX)
            val success =
                UnifiedSyncResult.Success(
                    provider = SyncBackendType.INBOX,
                    message = "Sync inbox conflicts resolved",
                )
            state.value =
                UnifiedSyncState.Success(
                    provider = SyncBackendType.INBOX,
                    timestamp = System.currentTimeMillis(),
                    summary = success.message,
                )
            success
        } else {
            val pendingConflictSet =
                conflictSet.copy(
                    files = remaining,
                    sessionKind = SyncConflictSessionKind.STANDARD_CONFLICT,
                )
            pendingConflictStore.write(pendingConflictSet)
            state.value = UnifiedSyncState.ConflictDetected(SyncBackendType.INBOX, pendingConflictSet)
            UnifiedSyncResult.Conflict(
                provider = SyncBackendType.INBOX,
                message = "Pending conflicts remain",
                conflicts = pendingConflictSet,
            )
        }
    }

    private suspend fun processPendingInbox(): UnifiedSyncResult {
        if (!preferencesRepository.isSyncInboxEnabled().first()) {
            state.value = UnifiedSyncState.Idle
            return UnifiedSyncResult.Success(
                provider = SyncBackendType.INBOX,
                message = "Sync inbox is disabled",
            )
        }

        val inboxRoot = workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX).first()
        if (inboxRoot == null) {
            return notConfiguredResult()
        }
        ensureInboxDirectoryStructure(context = context, inboxRoot = inboxRoot)

        pendingConflictStore.read(SyncBackendType.INBOX)?.let { pendingConflict ->
            state.value = UnifiedSyncState.Running(SyncBackendType.INBOX, UnifiedSyncPhase.INITIALIZING)
            val remainingPending = reprocessPendingConflictSet(inboxRoot, pendingConflict)
            if (remainingPending != null) {
                state.value = UnifiedSyncState.ConflictDetected(SyncBackendType.INBOX, remainingPending)
                return UnifiedSyncResult.Conflict(
                    provider = SyncBackendType.INBOX,
                    message = "Pending conflicts remain",
                    conflicts = remainingPending,
                )
            }
        }

        state.value = UnifiedSyncState.Running(SyncBackendType.INBOX, UnifiedSyncPhase.LISTING)
        return runCatching {
            val conflicts = mutableListOf<SyncConflictFile>()
            listInboxMarkdownFiles(context, inboxRoot).forEach { file ->
                processMarkdownFile(
                    inboxRoot = inboxRoot,
                    inboxFile = file,
                )?.let(conflicts::add)
            }
            if (conflicts.isNotEmpty()) {
                val conflictSet =
                    SyncConflictSet(
                        source = SyncBackendType.INBOX,
                        files = conflicts,
                        timestamp = System.currentTimeMillis(),
                    )
                pendingConflictStore.write(conflictSet)
                state.value = UnifiedSyncState.ConflictDetected(SyncBackendType.INBOX, conflictSet)
                return@runCatching UnifiedSyncResult.Conflict(
                    provider = SyncBackendType.INBOX,
                    message = "Sync inbox conflict detected",
                    conflicts = conflictSet,
                )
            }
            val result =
                UnifiedSyncResult.Success(
                    provider = SyncBackendType.INBOX,
                    message = "Sync inbox processed",
                )
            state.value =
                UnifiedSyncState.Success(
                    provider = SyncBackendType.INBOX,
                    timestamp = System.currentTimeMillis(),
                    summary = result.message,
                )
            result
        }.getOrElse { throwable ->
            val error =
                UnifiedSyncError(
                    provider = SyncBackendType.INBOX,
                    message = throwable.message ?: "Sync inbox failed",
                    cause = throwable,
                )
            state.value =
                UnifiedSyncState.Error(
                    error = error,
                    timestamp = System.currentTimeMillis(),
                )
            UnifiedSyncResult.Error(
                provider = SyncBackendType.INBOX,
                error = error,
            )
        }
    }

    private fun notConfiguredResult(): UnifiedSyncResult.NotConfigured {
        val error =
            UnifiedSyncError(
                provider = SyncBackendType.INBOX,
                message = "Sync inbox is not configured",
            )
        state.value = UnifiedSyncState.NotConfigured(SyncBackendType.INBOX)
        return UnifiedSyncResult.NotConfigured(
            provider = SyncBackendType.INBOX,
            error = error,
        )
    }

    private suspend fun processMarkdownFile(
        inboxRoot: String,
        inboxFile: InboxMarkdownFile,
    ): SyncConflictFile? {
        val imported =
            previewInboxMediaReferences(
                relativePath = inboxFile.relativePath,
                markdown = inboxFile.content,
            )
        val relativePath = inboxFile.relativePath
        val targetFilename = relativePath.substringAfterLast('/')
        val localContent = markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, targetFilename)
        val localLastModified =
            markdownStorageDataSource
                .getFileMetadataIn(MemoDirectoryType.MAIN, targetFilename)
                ?.lastModified
        val conflictFile =
            SyncConflictFile(
                relativePath = INBOX_PREFIX + relativePath,
                localContent = localContent,
                remoteContent = imported.rewrittenMarkdown,
                isBinary = false,
                localLastModified = localLastModified,
                remoteLastModified = inboxFile.lastModified,
            )
        val resolvedContent =
            when {
                localContent == null -> imported.rewrittenMarkdown
                localContent == imported.rewrittenMarkdown -> imported.rewrittenMarkdown
                else -> safeAutoResolvedContent(conflictFile)
            }
        if (resolvedContent == null) {
            return conflictFile
        }
        commitImportedFile(inboxRoot, relativePath, inboxFile.content, resolvedContent)
        return null
    }

    private suspend fun reprocessPendingConflictSet(
        inboxRoot: String,
        pendingConflict: SyncConflictSet,
    ): SyncConflictSet? {
        val safeChoices =
            pendingConflict.files.mapNotNull { conflictFile ->
                SyncConflictAutoResolutionAdvisor.safeAutoResolutionChoice(conflictFile)?.let { choice ->
                    conflictFile.relativePath to choice
                }
            }.toMap()
        if (safeChoices.isEmpty()) {
            return pendingConflict
        }

        val remaining =
            applyInboxConflictResolution(
                inboxRoot = inboxRoot,
                conflictSet = pendingConflict,
                resolution = SyncConflictResolution(safeChoices),
            )
        return if (remaining.isEmpty()) {
            pendingConflictStore.clear(SyncBackendType.INBOX)
            null
        } else {
            val remainingConflictSet =
                pendingConflict.copy(
                    files = remaining,
                    sessionKind = SyncConflictSessionKind.STANDARD_CONFLICT,
                )
            pendingConflictStore.write(remainingConflictSet)
            remainingConflictSet
        }
    }

    private suspend fun applyInboxConflictResolution(
        inboxRoot: String,
        conflictSet: SyncConflictSet,
        resolution: SyncConflictResolution,
    ): List<SyncConflictFile> =
        applyFileConflictChoices(
            conflictSet = conflictSet,
            resolution = resolution,
            defaultChoice = SyncConflictResolutionChoice.SKIP_FOR_NOW,
        ) { conflictFile, choice ->
            val relativePath = conflictFile.relativePath.removePrefix(INBOX_PREFIX)
            val inboxContent =
                readInboxTextFile(context, inboxRoot, relativePath)
                    ?: return@applyFileConflictChoices FileConflictApplication.Unresolved
            val targetContent =
                when (choice) {
                    SyncConflictResolutionChoice.KEEP_LOCAL -> conflictFile.localContent
                    SyncConflictResolutionChoice.KEEP_REMOTE -> conflictFile.remoteContent
                    SyncConflictResolutionChoice.MERGE_TEXT ->
                        SyncConflictTextMerge.merge(
                            localText = conflictFile.localContent,
                            remoteText = conflictFile.remoteContent,
                            localLastModified = conflictFile.localLastModified,
                            remoteLastModified = conflictFile.remoteLastModified,
                        )
                    SyncConflictResolutionChoice.SKIP_FOR_NOW -> null
                } ?: return@applyFileConflictChoices FileConflictApplication.Unresolved
            commitImportedFile(inboxRoot, relativePath, inboxContent, targetContent)
            FileConflictApplication.Applied(Unit)
        }.unresolvedFiles

    private suspend fun commitImportedFile(
        inboxRoot: String,
        relativePath: String,
        originalMarkdown: String,
        targetContent: String,
    ) {
        val imported =
            importInboxMediaReferences(
                context = context,
                workspaceMediaAccess = workspaceMediaAccess,
                inboxRoot = inboxRoot,
                relativePath = relativePath,
                markdown = originalMarkdown,
            )
        val targetFilename = relativePath.substringAfterLast('/')
        markdownStorageDataSource.saveFileIn(
            directory = MemoDirectoryType.MAIN,
            filename = targetFilename,
            content = targetContent,
            append = false,
        )
        memoSynchronizer.refreshImportedSync(targetFilename)
        deleteInboxFile(context = context, inboxRoot = inboxRoot, relativePath = relativePath)
        imported.importedAttachments.forEach { attachment ->
            deleteInboxFile(context = context, inboxRoot = inboxRoot, relativePath = attachment)
        }
    }
}
