package com.lomo.data.repository

import android.content.Context
import com.lomo.data.engine.media.PendingMediaStageRegistry
import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.engine.store.StoreMemoCommand
import com.lomo.data.engine.store.StoreMemoCommandKind
import com.lomo.data.engine.store.StorePort
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.SyncBackendType
import com.lomo.data.sync.SyncConflictSuggestionPort
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
import com.lomo.domain.repository.SyncInboxPreferencesRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.WorkspaceMutationLease
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber


internal const val INBOX_PREFIX = "inbox/"

internal const val WORKSPACE_WRITES_UNAVAILABLE_MESSAGE =
    "Workspace writes are unavailable"

data class SyncInboxRepositoryDependencies(
    val context: Context,
    val preferencesRepository: SyncInboxPreferencesRepository,
    val workspaceConfigSource: WorkspaceConfigSource,
    val pendingReviewStore: PendingSyncReviewStore,
    val storePort: StorePort,
    val pendingStages: PendingMediaStageRegistry,
    val workspaceRoot: WorkspaceFilesystemRoot,
    val committedMediaSink: CommittedMediaLocationSink,
)

class SyncInboxRepositoryImpl(
    dependencies: SyncInboxRepositoryDependencies,
    private val writeLease: WorkspaceMutationLease,
    private val contentProjector: com.lomo.data.util.MarkdownWorkspaceContentProjector,
    private val suggestionPort: SyncConflictSuggestionPort,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : SyncInboxRepository {
    private val context: Context = dependencies.context
    private val preferencesRepository: SyncInboxPreferencesRepository = dependencies.preferencesRepository
    private val workspaceConfigSource: WorkspaceConfigSource = dependencies.workspaceConfigSource
    private val pendingReviewStore: PendingSyncReviewStore = dependencies.pendingReviewStore
    private val storePort: StorePort = dependencies.storePort
    private val pendingStages: PendingMediaStageRegistry = dependencies.pendingStages
    private val workspaceRoot: WorkspaceFilesystemRoot = dependencies.workspaceRoot
    private val committedMediaSink: CommittedMediaLocationSink = dependencies.committedMediaSink

    private val state = MutableStateFlow<UnifiedSyncState>(UnifiedSyncState.Idle)
    private val pendingReviewRestorer =
        SyncInboxPendingReviewRestorer(
            context = context,
            pendingStages = pendingStages,
            workspaceRoot = workspaceRoot,
            contentProjector = contentProjector,
            suggestionPort = suggestionPort,
            dispatcherProvider = dispatcherProvider,
        )

    override fun syncState(): Flow<UnifiedSyncState> = state

    override suspend fun ensureDirectoryStructure() {
        val inboxRoot = workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX).first() ?: return
        ensureInboxDirectoryStructure(
            context = context,
            inboxRoot = inboxRoot,
            dispatcherProvider = dispatcherProvider,
        )
    }

    override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult {
        if (!preferencesRepository.isSyncInboxEnabled().first()) {
            state.value = UnifiedSyncState.Idle
            return UnifiedSyncResult.Success(
                provider = SyncBackendType.INBOX,
                message = "Sync inbox is disabled",
            )
        }
        // One admission for the whole drain, so a switch cannot split the batch across workspaces.
        return when (operation) {
            UnifiedSyncOperation.MANUAL_SYNC,
            UnifiedSyncOperation.REFRESH_SYNC,
            UnifiedSyncOperation.PROCESS_PENDING_CHANGES,
            -> writeLease.withWriteOrNull { processPendingInbox() } ?: workspaceWritesUnavailableResult()
        }
    }

    override suspend fun resolveReview(
        resolution: SyncReviewResolution,
        review: SyncReviewSession,
    ): UnifiedSyncResult =
        // One admission for the whole resolution: a switch mid-review would otherwise land some
        // files in the old workspace and the rest in the new one.
        writeLease.withWriteOrNull {
            state.value = UnifiedSyncState.Running(SyncBackendType.INBOX, UnifiedSyncPhase.INITIALIZING)
            val inboxRoot =
                workspaceConfigSource
                    .getRootFlow(StorageRootType.SYNC_INBOX)
                    .first()
                    ?: return@withWriteOrNull notConfiguredResult()

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
                        return@withWriteOrNull UnifiedSyncResult.Error(provider = SyncBackendType.INBOX, error = error)
                    }
                    is PendingSyncRestoreResult.Failed -> {
                        val error =
                            UnifiedSyncError(
                                provider = SyncBackendType.INBOX,
                                message =
                                    "Pending sync inbox review restore failed: " +
                                        (restored.error.category ?: restored.error.message),
                                cause = restored.error.cause,
                            )
                        state.value = UnifiedSyncState.Error(error = error, timestamp = System.currentTimeMillis())
                        return@withWriteOrNull UnifiedSyncResult.Error(provider = SyncBackendType.INBOX, error = error)
                    }
                }

            val remaining = applyInboxReviewResolution(inboxRoot, validatedReview, resolution)
            if (remaining.isEmpty()) {
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
        } ?: workspaceWritesUnavailableResult()

    private suspend fun processPendingInbox(): UnifiedSyncResult {
        val inboxRoot = workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX).first()
        if (inboxRoot == null) {
            return notConfiguredResult()
        }
        ensureInboxDirectoryStructure(context = context, inboxRoot = inboxRoot, dispatcherProvider = dispatcherProvider)

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
                            message =
                                "Pending sync inbox review restore failed: " +
                                    (restored.error.category ?: restored.error.message),
                            cause = restored.error.cause,
                        )
                    state.value = UnifiedSyncState.Error(error = error, timestamp = System.currentTimeMillis())
                    return UnifiedSyncResult.Error(provider = SyncBackendType.INBOX, error = error)
                }
            }
        }

        state.value = UnifiedSyncState.Running(SyncBackendType.INBOX, UnifiedSyncPhase.LISTING)
        return try {
            resolveBatchResult(processInboxBatch(inboxRoot))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val syncError =
                UnifiedSyncError(
                    provider = SyncBackendType.INBOX,
                    message = error.message ?: "Sync inbox failed",
                    cause = error,
                )
            state.value =
                UnifiedSyncState.Error(
                    error = syncError,
                    timestamp = System.currentTimeMillis(),
                )
            UnifiedSyncResult.Error(
                provider = SyncBackendType.INBOX,
                error = syncError,
            )
        }
    }

    private suspend fun processInboxBatch(inboxRoot: String): ProcessInboxBatchResult {
        val reviewFiles = mutableListOf<SyncReviewItem>()
        listInboxMarkdownFiles(context, inboxRoot, dispatcherProvider).forEach { file ->
            val reviewFile =
                try {
                    buildInboxReviewFile(
                        inboxRoot = inboxRoot,
                        inboxFile = file,
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    blockedInboxReviewFile(
                        relativePath = file.relativePath,
                        lastModified = file.lastModified,
                        message = error.message ?: "Cannot inspect sync inbox file",
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
                deleteInboxFile(
                    context = context,
                    inboxRoot = inboxRoot,
                    relativePath = attachment,
                    dispatcherProvider = dispatcherProvider,
                )
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

    private fun workspaceWritesUnavailableResult(): UnifiedSyncResult {
        val error =
            UnifiedSyncError(
                provider = SyncBackendType.INBOX,
                message = WORKSPACE_WRITES_UNAVAILABLE_MESSAGE,
            )
        state.value = UnifiedSyncState.Error(error = error, timestamp = System.currentTimeMillis())
        return UnifiedSyncResult.Error(provider = SyncBackendType.INBOX, error = error)
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
                dispatcherProvider = dispatcherProvider,
            ) ?: return blockedInboxReviewFile(
                relativePath = relativePath,
                lastModified = inboxFile.lastModified,
                message = "Missing inbox markdown file",
            )
        val staged =
            stageInboxMediaReferences(
                environment = inboxStagingEnvironment(inboxRoot),
                relativePath = relativePath,
                markdown = markdown,
            )
        // An inbox drop has no local counterpart: the import is a new memo through the session
        // command, so the review's local side stays absent rather than reading a workspace file.
        return SyncReviewItem(
            relativePath = INBOX_PREFIX + relativePath,
            localContent = null,
            incomingContent = staged.rewrittenMarkdown,
            isBinary = false,
            localLastModified = null,
            incomingLastModified = inboxFile.lastModified,
            state =
                if (staged.missingAttachments.isNotEmpty()) {
                    SyncReviewItemState.BLOCKED
                } else {
                    SyncReviewItemState.READY_TO_IMPORT
                },
            message = staged.missingAttachments.reviewMessageOrNull(),
            suggestion =
                suggestionPort.suggest(
                    localBody = null,
                    remoteBody = staged.rewrittenMarkdown,
                    localLastModifiedMs = null,
                    remoteLastModifiedMs = inboxFile.lastModified,
                    isBinary = false,
                ),
        )
    }

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
                pendingStages.releaseIncoming(inboxStageOwnerId(relativePath))
                deleteInboxFile(
                    context = context,
                    inboxRoot = inboxRoot,
                    relativePath = relativePath,
                    dispatcherProvider = dispatcherProvider,
                )
                return@forEach
            }
            val inboxContent =
                readInboxTextFile(context, inboxRoot, relativePath, dispatcherProvider)
                    ?: run {
                        unresolvedItems += item
                        return@forEach
                    }
            val targetContent =
                when (choice) {
                    SyncReviewResolutionChoice.KEEP_LOCAL -> null
                    SyncReviewResolutionChoice.KEEP_INCOMING -> item.incomingContent
                    SyncReviewResolutionChoice.MERGE_TEXT -> item.suggestion?.mergedText
                    SyncReviewResolutionChoice.SKIP_FOR_NOW -> null
                }
            if (targetContent == null) {
                unresolvedItems += item
                return@forEach
            }
            val committedFile = commitImportedFile(inboxRoot, item, relativePath, inboxContent, targetContent)
            if (committedFile != null) {
                committedFiles += committedFile
            } else {
                unresolvedItems += item
            }
        }
        cleanupImportedAttachments(inboxRoot, committedFiles)
        return unresolvedItems
    }

    /**
     * Commits one approved inbox file through the same session command a draft save uses: the
     * staged claims transfer to the frozen operation, [StoreMemoCommandKind.Create] publishes the
     * document and its artifact writes atomically, and the inbox source is deleted only after the
     * durable commit returns.
     */
    private fun inboxStagingEnvironment(inboxRoot: String): InboxStagingEnvironment =
        InboxStagingEnvironment(
            context = context,
            inboxRoot = inboxRoot,
            pendingStages = pendingStages,
            workspaceRoot = workspaceRoot,
            contentProjector = contentProjector,
            dispatcherProvider = dispatcherProvider,
        )

    private suspend fun commitImportedFile(
        inboxRoot: String,
        item: SyncReviewItem,
        relativePath: String,
        originalMarkdown: String,
        targetContent: String,
    ): CommittedInboxFile? {
        val staged =
            try {
                stageInboxMediaReferences(
                    environment = inboxStagingEnvironment(inboxRoot),
                    relativePath = relativePath,
                    markdown = originalMarkdown,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // behavior-contract: silent-result-ok: staging failure keeps the inbox item
                // pending so the next sync pass retries with the same operation identity.
                Timber.w(error, "Sync inbox staging failed for %s", relativePath)
                return null
            }
        // Fail closed on drift: the approved incoming content was frozen at preview; a changed or
        // newly unresolved file stays in the review instead of committing a different payload.
        if (staged.missingAttachments.isNotEmpty() || staged.rewrittenMarkdown != item.incomingContent) {
            return null
        }
        val operationId = inboxImportOperationId(relativePath, targetContent)
        val promotes = pendingStages.plansForIncomingOperation(operationId, inboxStageOwnerId(relativePath))
        try {
            storePort.applyMemoCommand(
                StoreMemoCommand(
                    operationId = operationId,
                    kind = StoreMemoCommandKind.Create,
                    memoId = "",
                    expectedRevision = 0L,
                    content = targetContent,
                    pendingPromotes = promotes,
                    chronologyEpochMs = item.incomingLastModified?.takeIf { it > 0 },
                ),
                onPublication = {},
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // behavior-contract: silent-result-ok: a failed session commit leaves the staged
            // leases and the inbox item intact so retry replays the same operation.
            Timber.w(error, "Sync inbox session commit failed for %s", relativePath)
            return null
        }
        committedMediaSink.publishCommittedMedia(promotes)
        deleteInboxFile(
            context = context,
            inboxRoot = inboxRoot,
            relativePath = relativePath,
            dispatcherProvider = dispatcherProvider,
        )
        return CommittedInboxFile(
            importedAttachmentsToDelete = staged.importedAttachments,
        )
    }
}

private data class CommittedInboxFile(
    val importedAttachmentsToDelete: List<String>,
)

private data class ProcessInboxBatchResult(
    val reviewFiles: List<SyncReviewItem>,
)
