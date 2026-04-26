package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.findDestructiveMemoBlock
import com.lomo.data.util.IndexedTextLines
import com.lomo.domain.model.Memo
import java.io.File

internal suspend fun flushMainMemoUpdateToFile(
    runtime: MemoMutationRuntime,
    storageFormatProvider: MemoStorageFormatProvider,
    memo: Memo,
    newContent: String,
): Boolean {
    requireSafeMemoDateKey(memo.dateKey)
    val filename = memo.dateKey + ".md"
    val currentFileContent = readCurrentMainFileContent(runtime, filename)
    return currentFileContent?.let { content ->
        buildUpdatedFileContent(storageFormatProvider, content, memo, newContent)?.let { updatedContent ->
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
    storageFormatProvider: MemoStorageFormatProvider,
    currentFileContent: String,
    memo: Memo,
    newContent: String,
): String? {
    val lines = IndexedTextLines.of(currentFileContent)
    val (startIndex, endIndex) = findDestructiveMemoBlock(lines, memo.rawContent, memo.id)
    if (startIndex == -1 || endIndex < startIndex) {
        return null
    }
    val replacementLines = buildUpdatedMemoLines(newContent, storageFormatProvider.formatTime(memo.timestamp))
    return rebuildMemoContent(
        lines = lines,
        startIndex = startIndex,
        endIndex = endIndex,
        replacementLines = replacementLines,
    )
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
    resolveSavedMainFileLastModified(runtime, filename, savedUri)?.let { lastModified ->
        upsertMainState(runtime, filename, lastModified, savedUri)
        runtime.s3LocalChangeRecorder.recordMemoUpsert(filename)
        runtime.webDavLocalChangeRecorder.recordMemoUpsert(filename)
    }
}

internal suspend fun appendMainMemoContentAndUpdateState(
    runtime: MemoMutationRuntime,
    filename: String,
    rawContent: String,
) {
    requireSafeMemoMarkdownFilename(filename)
    val savedUriString = appendMainMemoContent(runtime, filename, rawContent)
    upsertMainState(
        runtime = runtime,
        filename = filename,
        lastModified = resolveMainFileLastModified(runtime, filename, savedUriString),
        safUri = savedUriString,
    )
    runtime.s3LocalChangeRecorder.recordMemoUpsert(filename)
    runtime.webDavLocalChangeRecorder.recordMemoUpsert(filename)
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
    savedUriString: String? = null,
): Long =
    resolveSavedMainFileLastModified(runtime, filename, savedUriString) ?: System.currentTimeMillis()

private suspend fun resolveSavedMainFileLastModified(
    runtime: MemoMutationRuntime,
    filename: String,
    savedUriString: String?,
): Long? {
    savedUriString?.let { savedPath ->
        val file = File(savedPath)
        if (file.isAbsolute && file.exists()) {
            file.lastModified().takeIf { it > 0L }?.let { return it }
        }
    }
    return runtime.markdownStorageDataSource
        .getFileMetadataIn(MemoDirectoryType.MAIN, filename)
        ?.lastModified
}

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
