package com.lomo.data.repository

import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo

internal data class MarkdownFileSnapshot(
    val directory: MemoDirectoryType,
    val filename: String,
    val content: String?,
)

internal data class WorkspaceMediaFileSnapshot(
    val category: WorkspaceMediaCategory,
    val filename: String,
    val bytes: ByteArray?,
)

internal data class MemoVersionRestoreSnapshot(
    val markdownFiles: List<MarkdownFileSnapshot>,
    val mediaFiles: List<WorkspaceMediaFileSnapshot>,
)

internal suspend fun captureRestoreSnapshot(
    markdownStorageDataSource: MarkdownStorageDataSource,
    workspaceMediaAccess: WorkspaceMediaAccess,
    store: MemoVersionStore,
    revisionId: String,
    filename: String,
): MemoVersionRestoreSnapshot =
    MemoVersionRestoreSnapshot(
        markdownFiles =
            listOf(
                MarkdownFileSnapshot(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, filename),
                ),
                MarkdownFileSnapshot(
                    directory = MemoDirectoryType.TRASH,
                    filename = filename,
                    content = markdownStorageDataSource.readFileIn(MemoDirectoryType.TRASH, filename),
                ),
            ),
        mediaFiles =
            store
                .listAssetsForRevision(revisionId)
                .mapNotNull { asset ->
                    val category = asset.logicalPath.toAttachmentCategory() ?: return@mapNotNull null
                    val attachmentFilename = asset.logicalPath.substringAfterLast('/')
                    WorkspaceMediaFileSnapshot(
                        category = category,
                        filename = attachmentFilename,
                        bytes = workspaceMediaAccess.readFileBytes(category, attachmentFilename),
                    )
                }.distinctBy { snapshot -> snapshot.category to snapshot.filename },
    )

internal suspend fun rollbackRestoreSnapshot(
    snapshot: MemoVersionRestoreSnapshot,
    markdownStorageDataSource: MarkdownStorageDataSource,
    workspaceMediaAccess: WorkspaceMediaAccess,
) {
    snapshot.markdownFiles.forEach { fileSnapshot ->
        runCatching {
            restoreMarkdownFileSnapshot(markdownStorageDataSource, fileSnapshot)
        }
    }
    snapshot.mediaFiles.forEach { fileSnapshot ->
        runCatching {
            restoreWorkspaceMediaFileSnapshot(workspaceMediaAccess, fileSnapshot)
        }
    }
}

private suspend fun restoreMarkdownFileSnapshot(
    markdownStorageDataSource: MarkdownStorageDataSource,
    snapshot: MarkdownFileSnapshot,
) {
    val content = snapshot.content
    if (content == null) {
        markdownStorageDataSource.deleteFileIn(snapshot.directory, snapshot.filename)
        return
    }
    markdownStorageDataSource.saveFileIn(
        directory = snapshot.directory,
        filename = snapshot.filename,
        content = content,
        append = false,
    )
}

private suspend fun restoreWorkspaceMediaFileSnapshot(
    workspaceMediaAccess: WorkspaceMediaAccess,
    snapshot: WorkspaceMediaFileSnapshot,
) {
    val bytes = snapshot.bytes
    if (bytes == null) {
        workspaceMediaAccess.deleteFile(snapshot.category, snapshot.filename)
        return
    }
    workspaceMediaAccess.writeFile(
        category = snapshot.category,
        filename = snapshot.filename,
        bytes = bytes,
    )
}

internal suspend fun rewriteMemoIntoDirectory(
    markdownStorageDataSource: MarkdownStorageDataSource,
    directory: MemoDirectoryType,
    filename: String,
    currentMemo: Memo,
    replacementRawContent: String,
    memoTextProcessor: MemoTextProcessor,
) {
    val currentFileContent = markdownStorageDataSource.readFileIn(directory, filename)
    val updatedContent =
        if (currentFileContent.isNullOrBlank()) {
            replacementRawContent
        } else {
            val lines = currentFileContent.lines().toMutableList()
            val (startIndex, endIndex) =
                memoTextProcessor.findMemoBlock(
                    lines = lines,
                    rawContent = currentMemo.rawContent,
                    timestamp = currentMemo.timestamp,
                    memoId = currentMemo.id,
                )
            if (startIndex == -1 || endIndex < startIndex) {
                currentFileContent
                    .takeIf(String::isNotEmpty)
                    ?.let { "$it\n$replacementRawContent" }
                    ?: replacementRawContent
            } else {
                lines.subList(startIndex, endIndex + 1).clear()
                lines.addAll(startIndex, replacementRawContent.lines())
                lines.joinToString("\n")
            }
        }
    markdownStorageDataSource.saveFileIn(
        directory = directory,
        filename = filename,
        content = updatedContent,
        append = false,
    )
}

internal suspend fun removeMemoFromDirectoryIfPresent(
    markdownStorageDataSource: MarkdownStorageDataSource,
    directory: MemoDirectoryType,
    filename: String,
    memo: Memo,
    memoTextProcessor: MemoTextProcessor,
) {
    val currentFileContent = markdownStorageDataSource.readFileIn(directory, filename) ?: return
    val lines = currentFileContent.lines().toMutableList()
    val removed =
        memoTextProcessor.removeMemoBlockSafely(
            lines = lines,
            rawContent = memo.rawContent,
            memoId = memo.id,
        )
    if (!removed) {
        return
    }

    val updatedContent = lines.joinToString("\n")
    if (updatedContent.isBlank()) {
        markdownStorageDataSource.deleteFileIn(directory, filename)
        return
    }

    markdownStorageDataSource.saveFileIn(
        directory = directory,
        filename = filename,
        content = updatedContent,
        append = false,
    )
}
