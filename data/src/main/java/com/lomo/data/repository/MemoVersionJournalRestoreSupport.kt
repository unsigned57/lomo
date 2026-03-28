package com.lomo.data.repository

import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo

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
