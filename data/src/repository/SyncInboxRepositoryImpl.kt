package com.lomo.data.repository

import android.content.Context
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictTextMerge
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewSessionKind
import com.lomo.domain.model.UnifiedSyncError
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.SyncInboxRepository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber


internal const val INBOX_PREFIX = "inbox/"

class SyncInboxRepositoryImpl(
    private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val workspaceConfigSource: WorkspaceConfigSource,
    private val markdownStorageDataSource: MarkdownStorageDataSource,
    private val workspaceMediaAccess: WorkspaceMediaAccess,
    private val memoSynchronizer: MemoSynchronizer,
    private val pendingReviewStore: PendingSyncReviewStore,
) : SyncInboxRepository {
    private val state = MutableStateFlow<UnifiedSyncState>(UnifiedSyncState.Idle)
    private val pendingReviewRestorer =
        SyncInboxPendingReviewRestorer(
            context = context,
            markdownStorageDataSource = markdownStorageDataSource,
        )

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

    override suspend fun resolveReview(
        resolution: SyncReviewResolution,
        review: SyncReviewSession,
    ): UnifiedSyncResult {
        state.value = UnifiedSyncState.Running(SyncBackendType.INBOX, UnifiedSyncPhase.INITIALIZING)
        val inboxRoot =
            workspaceConfigSource
                .getRootFlow(StorageRootType.SYNC_INBOX)
                .first()
                ?: return notConfiguredResult()

        val validatedReview =
            when (val restored = pendingReviewStore.readDescriptor(SyncBackendType.INBOX)?.let { descriptor ->
                pendingReviewRestorer.restore(inboxRoot = inboxRoot, descriptor = descriptor)
            }) {
                null -> review
                is PendingSyncRestoreResult.Restored -> restored.session
                is PendingSyncRestoreResult.Invalidated -> {
                    pendingReviewStore.clear(SyncBackendType.INBOX)
                    val error =
                        UnifiedSyncError(
                            provider = SyncBackendType.INBOX,
                            message = "Pending sync inbox review requires rebuild: ${restored.reason}",
                        )
                    state.value = UnifiedSyncState.Error(error = error, timestamp = System.currentTimeMillis())
                    return UnifiedSyncResult.Error(provider = SyncBackendType.INBOX, error = error)
                }
                is PendingSyncRestoreResult.Failed -> {
                    val error =
                        UnifiedSyncError(
                            provider = SyncBackendType.INBOX,
                            message = "Pending sync inbox review restore failed: ${restored.error.category}",
                            cause = restored.error.cause,
                        )
                    state.value = UnifiedSyncState.Error(error = error, timestamp = System.currentTimeMillis())
                    return UnifiedSyncResult.Error(provider = SyncBackendType.INBOX, error = error)
                }
            }

        val remaining = applyInboxReviewResolution(inboxRoot, validatedReview, resolution)
        return if (remaining.isEmpty()) {
            pendingReviewStore.clear(SyncBackendType.INBOX)
            val success =
                UnifiedSyncResult.Success(
                    provider = SyncBackendType.INBOX,
                    message = "Sync inbox review resolved",
                )
            state.value =
                UnifiedSyncState.Success(
                    provider = SyncBackendType.INBOX,
                    timestamp = System.currentTimeMillis(),
                    summary = success.message,
            )
            success
        } else {
            val pendingReview = validatedReview.copy(items = remaining)
            pendingReviewStore.write(pendingReview)
            state.value = UnifiedSyncState.ReviewRequired(SyncBackendType.INBOX, pendingReview)
            UnifiedSyncResult.Review(
                provider = SyncBackendType.INBOX,
                message = "Pending sync inbox review",
                review = pendingReview,
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

        pendingReviewStore.readDescriptor(SyncBackendType.INBOX)?.let { descriptor ->
            when (val restored = pendingReviewRestorer.restore(inboxRoot = inboxRoot, descriptor = descriptor)) {
                is PendingSyncRestoreResult.Restored -> {
                    val pendingReview = restored.session
                    state.value = UnifiedSyncState.ReviewRequired(SyncBackendType.INBOX, pendingReview)
                    return UnifiedSyncResult.Review(
                        provider = SyncBackendType.INBOX,
                        message = "Pending sync inbox review",
                        review = pendingReview,
                    )
                }
                is PendingSyncRestoreResult.Invalidated -> {
                    pendingReviewStore.clear(SyncBackendType.INBOX)
                    Timber.i("Pending sync inbox review invalidated for rebuild: %s", restored.reason)
                }
                is PendingSyncRestoreResult.Failed -> {
                    val error =
                        UnifiedSyncError(
                            provider = SyncBackendType.INBOX,
                            message = "Pending sync inbox review restore failed: ${restored.error.category}",
                            cause = restored.error.cause,
                        )
                    state.value = UnifiedSyncState.Error(error = error, timestamp = System.currentTimeMillis())
                    return UnifiedSyncResult.Error(provider = SyncBackendType.INBOX, error = error)
                }
            }
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
        val reviewFiles = mutableListOf<SyncReviewItem>()
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
            reviewFiles.count { it.state == SyncReviewItemState.BLOCKED },
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
            val review =
                SyncReviewSession(
                    source = SyncBackendType.INBOX,
                    items = batchResult.reviewFiles,
                    timestamp = System.currentTimeMillis(),
                    kind = SyncReviewSessionKind.SYNC_INBOX_IMPORT_REVIEW,
                )
            pendingReviewStore.write(review)
            state.value = UnifiedSyncState.ReviewRequired(SyncBackendType.INBOX, review)
            return UnifiedSyncResult.Review(
                provider = SyncBackendType.INBOX,
                message = "Sync inbox review required",
                review = review,
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
    ): SyncReviewItem {
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
                missingAttachments.isNotEmpty() -> SyncReviewItemState.BLOCKED
                localContent == null -> SyncReviewItemState.READY_TO_IMPORT
                localContent == imported.rewrittenMarkdown -> SyncReviewItemState.READY_TO_IMPORT
                else -> SyncReviewItemState.CONTENT_DIFFERENCE
            }
        return SyncReviewItem(
            relativePath = INBOX_PREFIX + relativePath,
            localContent = localContent,
            incomingContent = imported.rewrittenMarkdown,
            isBinary = false,
            localLastModified = localLastModified,
            incomingLastModified = inboxFile.lastModified,
            state = reviewState,
            message = missingAttachments.reviewMessageOrNull(),
        )
    }

    private fun blockedInboxReviewFile(
        relativePath: String,
        lastModified: Long,
        message: String,
    ): SyncReviewItem =
        SyncReviewItem(
            relativePath = INBOX_PREFIX + relativePath,
            localContent = null,
            incomingContent = null,
            isBinary = false,
            incomingLastModified = lastModified,
            state = SyncReviewItemState.BLOCKED,
            message = message,
        )

    private fun List<String>.reviewMessageOrNull(): String? =
        takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "Missing attachments: ")

    private suspend fun applyInboxReviewResolution(
        inboxRoot: String,
        review: SyncReviewSession,
        resolution: SyncReviewResolution,
    ): List<SyncReviewItem> {
        val committedFiles = mutableListOf<CommittedInboxFile>()
        val unresolvedItems = mutableListOf<SyncReviewItem>()
        review.items.forEach { item ->
            val choice = resolution.perItemChoices[item.relativePath] ?: SyncReviewResolutionChoice.SKIP_FOR_NOW
            if (item.state == SyncReviewItemState.BLOCKED || choice == SyncReviewResolutionChoice.SKIP_FOR_NOW) {
                unresolvedItems += item
                return@forEach
            }
            val relativePath = item.relativePath.removePrefix(INBOX_PREFIX)
            if (choice == SyncReviewResolutionChoice.KEEP_LOCAL) {
                deleteInboxFile(context = context, inboxRoot = inboxRoot, relativePath = relativePath)
                return@forEach
            }
            val inboxContent =
                readInboxTextFile(context, inboxRoot, relativePath)
                    ?: run {
                        unresolvedItems += item
                        return@forEach
                    }
            val targetContent =
                when (choice) {
                    SyncReviewResolutionChoice.KEEP_LOCAL -> null
                    SyncReviewResolutionChoice.KEEP_INCOMING -> item.incomingContent
                    SyncReviewResolutionChoice.MERGE_TEXT ->
                        SyncConflictTextMerge.merge(
                            localText = item.localContent,
                            remoteText = item.incomingContent,
                            localLastModified = item.localLastModified,
                            remoteLastModified = item.incomingLastModified,
                        )
                    SyncReviewResolutionChoice.SKIP_FOR_NOW -> null
                }
            if (targetContent == null) {
                unresolvedItems += item
                return@forEach
            }
            val committedFile = commitImportedFile(inboxRoot, relativePath, inboxContent, targetContent)
            if (committedFile != null) {
                committedFiles += committedFile
            } else {
                unresolvedItems += item
            }
        }
        if (committedFiles.isNotEmpty()) {
            memoSynchronizer.refreshImportedSync()
        }
        cleanupImportedAttachments(inboxRoot, committedFiles)
        return unresolvedItems
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
    val reviewFiles: List<SyncReviewItem>,
)
