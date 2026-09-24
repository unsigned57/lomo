package com.lomo.data.repository

import android.content.Context
import com.lomo.data.engine.media.PendingMediaStageRegistry
import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.sync.SyncConflictSuggestionPort
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider

internal class SyncInboxPendingReviewRestorer(
    private val context: Context,
    private val pendingStages: PendingMediaStageRegistry,
    private val workspaceRoot: WorkspaceFilesystemRoot,
    private val contentProjector: com.lomo.data.util.MarkdownWorkspaceContentProjector,
    private val suggestionPort: SyncConflictSuggestionPort,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) {
    suspend fun restore(
        inboxRoot: String,
        descriptor: PendingSyncReviewDescriptor,
    ): PendingSyncRestoreResult<SyncReviewSession> {
        val inboxFilesByPath =
            listInboxMarkdownFiles(context = context, inboxRoot = inboxRoot, dispatcherProvider = dispatcherProvider)
                .associateBy { it.relativePath }
        val restoredItems = mutableListOf<SyncReviewItem>()
        var invalidation: PendingSyncInvalidationReason? = null
        val iterator = descriptor.items.iterator()
        while (invalidation == null && iterator.hasNext()) {
            when (val restored = restoreItem(inboxRoot, inboxFilesByPath, iterator.next())) {
                is InboxReviewItemRestore.Invalidated -> invalidation = restored.reason
                is InboxReviewItemRestore.Restored -> restoredItems += restored.item
            }
        }
        return invalidation?.let { reason -> PendingSyncRestoreResult.Invalidated(reason) }
            ?: PendingSyncRestoreResult.Restored(
                SyncReviewSession(
                    source = descriptor.source,
                    items = restoredItems,
                    timestamp = descriptor.timestamp,
                    kind = descriptor.kind,
                ),
            )
    }

    private suspend fun restoreItem(
        inboxRoot: String,
        inboxFilesByPath: Map<String, InboxMarkdownFileMetadata>,
        item: PendingSyncReviewItemDescriptor,
    ): InboxReviewItemRestore {
        val inboxRelativePath = item.relativePath.removePrefix(INBOX_PREFIX)
        val inboxContent =
            readInboxTextFile(
                context = context,
                inboxRoot = inboxRoot,
                relativePath = inboxRelativePath,
                dispatcherProvider = dispatcherProvider,
            )
        val inboxMetadata = inboxFilesByPath[inboxRelativePath]
        return when {
            inboxContent == null || inboxMetadata == null ->
                InboxReviewItemRestore.Invalidated(PendingSyncInvalidationReason.MISSING_REMOTE)
            else -> restoreExistingItem(item, inboxRoot, inboxRelativePath, inboxContent, inboxMetadata)
        }
    }

    private suspend fun restoreExistingItem(
        item: PendingSyncReviewItemDescriptor,
        inboxRoot: String,
        inboxRelativePath: String,
        inboxContent: String,
        inboxMetadata: InboxMarkdownFileMetadata,
    ): InboxReviewItemRestore {
        // Re-derive the staged rewrite: the same durable stage ledger and owner-resolved
        // destinations make a restart byte-identical, so any mismatch is a stale capture.
        val staged =
            stageInboxMediaReferences(
                environment =
                    InboxStagingEnvironment(
                        context = context,
                        inboxRoot = inboxRoot,
                        pendingStages = pendingStages,
                        workspaceRoot = workspaceRoot,
                        contentProjector = contentProjector,
                        dispatcherProvider = dispatcherProvider,
                    ),
                relativePath = inboxRelativePath,
                markdown = inboxContent,
            )
        val incomingBytes = staged.rewrittenMarkdown.toByteArray(Charsets.UTF_8)
        return when {
            !item.incoming.matchesRemote(
                actualEtag = incomingBytes.md5Hex(),
                actualLastModified = inboxMetadata.lastModified,
                actualSize = incomingBytes.size.toLong(),
            ) -> InboxReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_REMOTE)
            !item.incoming.matchesContent(staged.rewrittenMarkdown) ->
                InboxReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_REMOTE)
            else -> restoreLocalItem(item, staged.rewrittenMarkdown, inboxMetadata.lastModified)
        }
    }

    private fun restoreLocalItem(
        item: PendingSyncReviewItemDescriptor,
        incomingContent: String,
        incomingLastModified: Long,
    ): InboxReviewItemRestore =
        // Inbox imports carry no local counterpart: the local side must have been captured absent.
        // A descriptor captured with a local file predates the session-command import law and is
        // rebuilt rather than resolved against a stale verbatim document.
        if (!item.local.wasAbsentWhenCaptured()) {
            InboxReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_LOCAL)
        } else {
            InboxReviewItemRestore.Restored(
                SyncReviewItem(
                    relativePath = item.relativePath,
                    localContent = null,
                    incomingContent = incomingContent,
                    isBinary = item.isBinary,
                    localLastModified = null,
                    incomingLastModified = incomingLastModified,
                    state = item.state,
                    message = item.message,
                    suggestion =
                        suggestionPort.suggest(
                            localBody = null,
                            remoteBody = incomingContent,
                            localLastModifiedMs = null,
                            remoteLastModifiedMs = incomingLastModified,
                            isBinary = item.isBinary,
                        ),
                ),
            )
        }
}

private fun PendingSyncSideMetadata.wasAbsentWhenCaptured(): Boolean =
    etag == null &&
        lastModified == null &&
        size == null &&
        contentHash == null

private sealed interface InboxReviewItemRestore {
    data class Restored(
        val item: SyncReviewItem,
    ) : InboxReviewItemRestore

    data class Invalidated(
        val reason: PendingSyncInvalidationReason,
    ) : InboxReviewItemRestore
}
