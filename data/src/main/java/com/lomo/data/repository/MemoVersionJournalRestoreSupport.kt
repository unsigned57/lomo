package com.lomo.data.repository

import com.lomo.domain.model.Memo
import java.io.OutputStream

internal suspend fun MemoVersionJournal.buildRevisionRestoreCommand(
    currentMemo: Memo,
    revisionId: String,
): MemoLifecycleCommand {
    val revision = requireNotNull(store.getRevision(revisionId)) { "Revision not found: $revisionId" }
    val rawContent =
        readMemoVersionBlobContent(
            store = store,
            blobRoot = blobRoot,
            blobHash = revision.rawMarkdownBlobHash,
        )
    return MemoLifecycleCommand.restoreRevision(
        currentMemo = currentMemo,
        currentRevisionId = currentRevisionIdFor(currentMemo),
        targetRevisionId = revisionId,
        targetLifecycleState = revision.lifecycleState,
        targetMemo = revision.toMemo(rawContent, memoTextProcessor),
        targetRawContent = rawContent,
    )
}

internal suspend fun MemoVersionJournal.readRevisionRestoreAssets(
    revisionId: String,
): List<MemoRevisionRestoreAsset> =
    store
        .listAssetsForRevision(revisionId)
        .mapNotNull { asset ->
            val category = asset.logicalPath.toAttachmentCategory() ?: return@mapNotNull null
            MemoRevisionRestoreAsset(
                category = category,
                filename = asset.logicalPath.substringAfterLast('/'),
                writeTo = { output: OutputStream ->
                    output.write(
                        readMemoVersionBlobBytes(
                            store = store,
                            blobRoot = blobRoot,
                            blobHash = asset.blobHash,
                        ),
                    )
                },
            )
        }

private suspend fun MemoVersionJournal.currentRevisionIdFor(currentMemo: Memo): String =
    store
        .getLatestRevisionForMemo(currentMemo.id)
        ?.revisionId
        ?: "current-${currentMemo.rawContent.toVersionHash()}"
