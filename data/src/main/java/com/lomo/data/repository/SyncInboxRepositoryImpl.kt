package com.lomo.data.repository

import android.content.Context
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictFileReviewState
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
import timber.log.Timber
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
                    sessionKind = SyncConflictSessionKind.SYNC_INBOX_REVIEW,
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
            val reviewSet =
                pendingConflict.copy(sessionKind = SyncConflictSessionKind.SYNC_INBOX_REVIEW)
            state.value = UnifiedSyncState.ConflictDetected(SyncBackendType.INBOX, reviewSet)
            return UnifiedSyncResult.Conflict(
                provider = SyncBackendType.INBOX,
                message = "Pending sync inbox review",
                conflicts = reviewSet,
            )
        }

        state.value = UnifiedSyncState.Running(SyncBackendType.INBOX, UnifiedSyncPhase.LISTING)
        return runCatching {
            resolveBatchResult(processInboxBatch(inboxRoot))
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

    private suspend fun processInboxBatch(inboxRoot: String): ProcessInboxBatchResult {
        val reviewFiles = mutableListOf<SyncConflictFile>()
        listInboxMarkdownFiles(context, inboxRoot).forEach { file ->
            val reviewFile =
                runCatching {
                    buildInboxReviewFile(
                        inboxRoot = inboxRoot,
                        inboxFile = file,
                    )
                }.getOrElse { throwable ->
                    blockedInboxReviewFile(
                        relativePath = file.relativePath,
                        lastModified = file.lastModified,
                        message = throwable.message ?: "Cannot inspect sync inbox file",
                    )
                }
            reviewFiles += reviewFile
        }
        Timber.i(
            "SyncInbox reviewFiles=%d blocked=%d",
            reviewFiles.size,
            reviewFiles.count { it.reviewState == SyncConflictFileReviewState.BLOCKED },
        )
        return ProcessInboxBatchResult(
            reviewFiles = reviewFiles,
        )
    }

    private suspend fun cleanupImportedAttachments(
        inboxRoot: String,
        committedFiles: List<CommittedInboxFile>,
    ) {
        committedFiles
            .asSequence()
            .flatMap { committed -> committed.importedAttachmentsToDelete.asSequence() }
            .distinct()
            .forEach { attachment ->
                deleteInboxFile(context = context, inboxRoot = inboxRoot, relativePath = attachment)
            }
    }

    private suspend fun resolveBatchResult(batchResult: ProcessInboxBatchResult): UnifiedSyncResult {
        if (batchResult.reviewFiles.isNotEmpty()) {
            val conflictSet =
                SyncConflictSet(
                    source = SyncBackendType.INBOX,
                    files = batchResult.reviewFiles,
                    timestamp = System.currentTimeMillis(),
                    sessionKind = SyncConflictSessionKind.SYNC_INBOX_REVIEW,
                )
            pendingConflictStore.write(conflictSet)
            state.value = UnifiedSyncState.ConflictDetected(SyncBackendType.INBOX, conflictSet)
            return UnifiedSyncResult.Conflict(
                provider = SyncBackendType.INBOX,
                message = "Sync inbox review required",
                conflicts = conflictSet,
            )
        }
        val result =
            UnifiedSyncResult.Success(
                provider = SyncBackendType.INBOX,
                message = "No sync inbox changes",
            )
        state.value =
            UnifiedSyncState.Success(
                provider = SyncBackendType.INBOX,
                timestamp = System.currentTimeMillis(),
                summary = result.message,
            )
        return result
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

    private suspend fun buildInboxReviewFile(
        inboxRoot: String,
        inboxFile: InboxMarkdownFileMetadata,
    ): SyncConflictFile {
        val relativePath = inboxFile.relativePath
        val markdown =
            readInboxTextFile(
                context = context,
                inboxRoot = inboxRoot,
                relativePath = relativePath,
            ) ?: return blockedInboxReviewFile(
                relativePath = relativePath,
                lastModified = inboxFile.lastModified,
                message = "Missing inbox markdown file",
            )
        val imported =
            previewInboxMediaReferences(
                markdown = markdown,
            )
        val missingAttachments =
            missingInboxMediaReferences(
                context = context,
                inboxRoot = inboxRoot,
                markdown = markdown,
            )
        val targetFilename = relativePath.substringAfterLast('/')
        val localContent = markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, targetFilename)
        val localLastModified =
            markdownStorageDataSource
                .getFileMetadataIn(MemoDirectoryType.MAIN, targetFilename)
                ?.lastModified
        val reviewState =
            when {
                missingAttachments.isNotEmpty() -> SyncConflictFileReviewState.BLOCKED
                localContent == null -> SyncConflictFileReviewState.READY_TO_IMPORT
                localContent == imported.rewrittenMarkdown -> SyncConflictFileReviewState.READY_TO_IMPORT
                else -> SyncConflictFileReviewState.CONTENT_DIFFERENCE
            }
        return SyncConflictFile(
            relativePath = INBOX_PREFIX + relativePath,
            localContent = localContent,
            remoteContent = imported.rewrittenMarkdown,
            isBinary = false,
            localLastModified = localLastModified,
            remoteLastModified = inboxFile.lastModified,
            reviewState = reviewState,
            reviewMessage = missingAttachments.reviewMessageOrNull(),
        )
    }

    private fun blockedInboxReviewFile(
        relativePath: String,
        lastModified: Long,
        message: String,
    ): SyncConflictFile =
        SyncConflictFile(
            relativePath = INBOX_PREFIX + relativePath,
            localContent = null,
            remoteContent = null,
            isBinary = false,
            remoteLastModified = lastModified,
            reviewState = SyncConflictFileReviewState.BLOCKED,
            reviewMessage = message,
        )

    private fun List<String>.reviewMessageOrNull(): String? =
        takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "Missing attachments: ")

    private suspend fun applyInboxConflictResolution(
        inboxRoot: String,
        conflictSet: SyncConflictSet,
        resolution: SyncConflictResolution,
    ): List<SyncConflictFile> {
        val committedFiles = mutableListOf<CommittedInboxFile>()
        val unresolvedFiles =
            applyFileConflictChoices(
                conflictSet = conflictSet,
                resolution = resolution,
                defaultChoice = SyncConflictResolutionChoice.SKIP_FOR_NOW,
            ) { conflictFile, choice ->
                val relativePath = conflictFile.relativePath.removePrefix(INBOX_PREFIX)
                if (choice == SyncConflictResolutionChoice.KEEP_LOCAL) {
                    deleteInboxFile(context = context, inboxRoot = inboxRoot, relativePath = relativePath)
                    return@applyFileConflictChoices FileConflictApplication.Applied(Unit)
                }
                val inboxContent =
                    readInboxTextFile(context, inboxRoot, relativePath)
                        ?: return@applyFileConflictChoices FileConflictApplication.Unresolved
                val targetContent =
                    when (choice) {
                        SyncConflictResolutionChoice.KEEP_LOCAL -> null
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
                val committedFile = commitImportedFile(inboxRoot, relativePath, inboxContent, targetContent)
                if (committedFile != null) {
                    committedFiles += committedFile
                    FileConflictApplication.Applied(Unit)
                } else {
                    FileConflictApplication.Unresolved
                }
            }.unresolvedFiles
        if (committedFiles.isNotEmpty()) {
            memoSynchronizer.refreshImportedSync()
        }
        committedFiles
            .asSequence()
            .flatMap { committed -> committed.importedAttachmentsToDelete.asSequence() }
            .distinct()
            .forEach { attachment ->
                deleteInboxFile(context = context, inboxRoot = inboxRoot, relativePath = attachment)
            }
        return unresolvedFiles
    }

    private suspend fun commitImportedFile(
        inboxRoot: String,
        relativePath: String,
        originalMarkdown: String,
        targetContent: String,
    ): CommittedInboxFile? {
        val importResult =
            importInboxMediaReferences(
                context = context,
                workspaceMediaAccess = workspaceMediaAccess,
                inboxRoot = inboxRoot,
                markdown = originalMarkdown,
            )
        val imported =
            when (importResult) {
                is InboxMediaImportResult.Success -> importResult.preview
                is InboxMediaImportResult.MissingAttachments -> return null
            }
        val targetFilename = relativePath.substringAfterLast('/')
        markdownStorageDataSource.saveFileIn(
            directory = MemoDirectoryType.MAIN,
            filename = targetFilename,
            content = targetContent,
            append = false,
        )
        deleteInboxFile(context = context, inboxRoot = inboxRoot, relativePath = relativePath)
        return CommittedInboxFile(
            importedAttachmentsToDelete = imported.importedAttachments,
        )
    }
}

private data class CommittedInboxFile(
    val importedAttachmentsToDelete: List<String>,
)

private data class ProcessInboxBatchResult(
    val reviewFiles: List<SyncConflictFile>,
)
