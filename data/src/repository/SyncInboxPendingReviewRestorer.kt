package com.lomo.data.repository

import android.content.Context
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewSession

internal class SyncInboxPendingReviewRestorer(
    private val context: Context,
    private val markdownStorageDataSource: MarkdownStorageDataSource,
) {
    suspend fun restore(
        inboxRoot: String,
        descriptor: PendingSyncReviewDescriptor,
    ): PendingSyncRestoreResult<SyncReviewSession> {
        val inboxFilesByPath =
            listInboxMarkdownFiles(context = context, inboxRoot = inboxRoot)
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
            )
        val inboxMetadata = inboxFilesByPath[inboxRelativePath]
        return when {
            inboxContent == null || inboxMetadata == null ->
                InboxReviewItemRestore.Invalidated(PendingSyncInvalidationReason.MISSING_REMOTE)
            else -> restoreExistingItem(item, inboxRelativePath, inboxContent, inboxMetadata)
        }
    }

    private suspend fun restoreExistingItem(
        item: PendingSyncReviewItemDescriptor,
        inboxRelativePath: String,
        inboxContent: String,
        inboxMetadata: InboxMarkdownFileMetadata,
    ): InboxReviewItemRestore {
        val imported = previewInboxMediaReferences(markdown = inboxContent)
        val incomingBytes = imported.rewrittenMarkdown.toByteArray(Charsets.UTF_8)
        return when {
            !item.incoming.matchesRemote(
                actualEtag = incomingBytes.md5Hex(),
                actualLastModified = inboxMetadata.lastModified,
                actualSize = incomingBytes.size.toLong(),
            ) -> InboxReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_REMOTE)
            !item.incoming.matchesContent(imported.rewrittenMarkdown) ->
                InboxReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_REMOTE)
            else -> restoreLocalItem(item, inboxRelativePath, imported.rewrittenMarkdown, inboxMetadata.lastModified)
        }
    }

    private suspend fun restoreLocalItem(
        item: PendingSyncReviewItemDescriptor,
        inboxRelativePath: String,
        incomingContent: String,
        incomingLastModified: Long,
    ): InboxReviewItemRestore {
        val targetFilename = inboxRelativePath.substringAfterLast('/')
        val localContent = markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, targetFilename)
        val localLastModified =
            markdownStorageDataSource
                .getFileMetadataIn(MemoDirectoryType.MAIN, targetFilename)
                ?.lastModified
        val localBytes = localContent?.toByteArray(Charsets.UTF_8)
        return when {
            item.local.wasAbsentWhenCaptured() && localContent != null ->
                InboxReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_LOCAL)
            !item.local.wasAbsentWhenCaptured() &&
                !item.local.matchesRemote(
                    actualEtag = localBytes?.md5Hex(),
                    actualLastModified = localLastModified,
                    actualSize = localBytes?.size?.toLong(),
                ) -> InboxReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_LOCAL)
            !item.local.wasAbsentWhenCaptured() && !item.local.matchesContent(localContent) ->
                InboxReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_LOCAL)
            else ->
                InboxReviewItemRestore.Restored(
                    SyncReviewItem(
                        relativePath = item.relativePath,
                        localContent = localContent,
                        incomingContent = incomingContent,
                        isBinary = item.isBinary,
                        localLastModified = localLastModified,
                        incomingLastModified = incomingLastModified,
                        state = item.state,
                        message = item.message,
                    ),
                )
        }
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
