package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.source.MemoDirectoryType
import com.lomo.domain.model.Memo

internal suspend fun flushMainMemoUpdateToFile(
    runtime: MemoMutationRuntime,
    storageFormatProvider: MemoStorageFormatProvider,
    memo: Memo,
    newContent: String,
): Boolean {
    val filename = memo.dateKey + ".md"
    val currentFileContent = readCurrentMainFileContent(runtime, filename)
    return currentFileContent?.let { content ->
        buildUpdatedFileContent(runtime, storageFormatProvider, content, memo, newContent)?.let { updatedContent ->
            persistUpdatedMainFile(runtime, filename, updatedContent)
            true
        } ?: false
    } ?: false
}

internal suspend fun readCurrentMainFileContent(
    runtime: MemoMutationRuntime,
    filename: String,
): String? {
    val cachedUri = getMainSafUri(runtime, filename).toPersistedUriOrNull()
    return if (cachedUri != null) {
        runtime.markdownStorageDataSource.readFile(cachedUri)
            ?: runtime.markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
    } else {
        runtime.markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
    }
}

internal suspend fun buildUpdatedFileContent(
    runtime: MemoMutationRuntime,
    storageFormatProvider: MemoStorageFormatProvider,
    currentFileContent: String,
    memo: Memo,
    newContent: String,
): String? {
    val lines = currentFileContent.lines().toMutableList()
    val success =
        runtime.textProcessor.replaceMemoBlockSafely(
            lines = lines,
            rawContent = memo.rawContent,
            newRawContent = newContent,
            timestampStr = storageFormatProvider.formatTime(memo.timestamp),
            memoId = memo.id,
        )
    return lines.joinToString("\n").takeIf { success }
}

internal suspend fun persistUpdatedMainFile(
    runtime: MemoMutationRuntime,
    filename: String,
    updatedContent: String,
) {
    val savedUri =
        runtime.markdownStorageDataSource.saveFileIn(
            directory = MemoDirectoryType.MAIN,
            filename = filename,
            content = updatedContent,
            append = false,
        )
    runtime.markdownStorageDataSource
        .getFileMetadataIn(MemoDirectoryType.MAIN, filename)
        ?.let { metadata ->
            upsertMainState(runtime, filename, metadata.lastModified, savedUri)
            runtime.s3LocalChangeRecorder.recordMemoUpsert(filename)
        }
}

internal suspend fun appendMainMemoContentAndUpdateState(
    runtime: MemoMutationRuntime,
    filename: String,
    rawContent: String,
) {
    val savedUriString = appendMainMemoContent(runtime, filename, rawContent)
    upsertMainState(runtime, filename, resolveMainFileLastModified(runtime, filename), savedUriString)
    runtime.s3LocalChangeRecorder.recordMemoUpsert(filename)
}

internal suspend fun appendMainMemoContent(
    runtime: MemoMutationRuntime,
    filename: String,
    rawContent: String,
): String? {
    val cachedUri = getMainSafUri(runtime, filename).toPersistedUriOrNull()
    return runtime.markdownStorageDataSource.saveFileIn(
        directory = MemoDirectoryType.MAIN,
        filename = filename,
        content = "\n$rawContent",
        append = true,
        uri = cachedUri,
    )
}

internal suspend fun getMainSafUri(
    runtime: MemoMutationRuntime,
    filename: String,
): String? = runtime.localFileStateDao.getByFilename(filename, false)?.safUri

internal suspend fun resolveMainFileLastModified(
    runtime: MemoMutationRuntime,
    filename: String,
): Long =
    runtime.markdownStorageDataSource
        .getFileMetadataIn(MemoDirectoryType.MAIN, filename)
        ?.lastModified
        ?: System.currentTimeMillis()

internal fun String?.toPersistedUriOrNull(): Uri? =
    this
        ?.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        ?.let(Uri::parse)

internal suspend fun upsertMainState(
    runtime: MemoMutationRuntime,
    filename: String,
    lastModified: Long,
    safUri: String? = null,
) {
    val existing = runtime.localFileStateDao.getByFilename(filename, false)
    runtime.localFileStateDao.upsert(
        LocalFileStateEntity(
            filename = filename,
            isTrash = false,
            safUri = safUri ?: existing?.safUri,
            lastKnownModifiedTime = lastModified,
        ),
    )
}
