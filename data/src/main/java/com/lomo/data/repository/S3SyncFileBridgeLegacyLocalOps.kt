package com.lomo.data.repository

import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import java.io.File
import java.nio.charset.StandardCharsets

internal suspend fun legacyLocalFiles(
    runtime: S3SyncRepositoryContext,
    layout: SyncDirectoryLayout,
): Map<String, LocalS3File> {
    val memoPrefix = legacyMemoRemotePrefix(layout)
    val memoFiles =
        runtime.markdownStorageDataSource
            .listMetadataIn(MemoDirectoryType.MAIN)
            .filter { it.filename.endsWith(S3_MEMO_SUFFIX) }
            .associate { metadata ->
                val path = "$memoPrefix${metadata.filename}"
                path to LocalS3File(path, metadata.lastModified)
            }
    val mediaFiles =
        runtime.localMediaSyncStore
            .listFiles(layout)
            .mapKeys { (path, _) -> "$S3_ROOT/$path" }
            .mapValues { (path, metadata) ->
                LocalS3File(path, metadata.lastModified, metadata.size)
            }
    return memoFiles + mediaFiles
}

internal suspend fun legacyReadLocalBytes(
    runtime: S3SyncRepositoryContext,
    path: String,
    layout: SyncDirectoryLayout,
): ByteArray? =
    if (legacyIsMemoPath(path, layout)) {
        runtime.markdownStorageDataSource
            .readFileIn(MemoDirectoryType.MAIN, legacyExtractMemoFilename(path, layout))
            ?.toByteArray(StandardCharsets.UTF_8)
    } else {
        runNonFatalCatching {
            runtime.localMediaSyncStore.readBytes(path, layout)
        }.getOrNull()
    }

internal suspend fun legacyLocalFile(
    runtime: S3SyncRepositoryContext,
    path: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode.Legacy,
): LocalS3File? =
    if (legacyIsMemoPath(path, layout)) {
        runtime.markdownStorageDataSource
            .getFileMetadataIn(MemoDirectoryType.MAIN, legacyExtractMemoFilename(path, layout))
            ?.let { metadata ->
                LocalS3File(path = path, lastModified = metadata.lastModified)
            }
    } else {
        legacyDirectMediaLocalFile(path, layout, mode)
            ?: runtime.localMediaSyncStore
                .listFiles(layout)[path.removePrefix("$S3_ROOT/")]
                ?.let { metadata ->
                    LocalS3File(path = path, lastModified = metadata.lastModified, size = metadata.size)
                }
    }

private fun legacyDirectMediaLocalFile(
    path: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode.Legacy,
): LocalS3File? {
    val targetFile = legacyDirectMediaFile(path, layout, mode) ?: return null
    return if (targetFile.exists() && targetFile.isFile) {
        LocalS3File(path = path, lastModified = targetFile.lastModified(), size = targetFile.length())
    } else {
        null
    }
}

internal suspend fun legacyExportLocalFile(
    runtime: S3SyncRepositoryContext,
    path: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode.Legacy,
    session: S3SyncTransferSession,
): S3TransferFile? =
    if (legacyIsMemoPath(path, layout)) {
        val content =
            runtime.markdownStorageDataSource
                .readFileIn(MemoDirectoryType.MAIN, legacyExtractMemoFilename(path, layout))
                ?: return null
        val tempFile = session.createTempFile("s3-memo-upload-", ".md")
        tempFile.writeText(content, StandardCharsets.UTF_8)
        S3TransferFile(tempFile)
    } else {
        legacyDirectMediaFile(path, layout, mode)?.let(::S3TransferFile)
            ?: run {
                val tempFile = session.createTempFile("s3-media-upload-", path.transferSuffix())
                runNonFatalCatching {
                    runtime.localMediaSyncStore.exportToFile(path, layout, tempFile)
                }.getOrNull() ?: return null
                S3TransferFile(tempFile)
            }
    }

internal suspend fun legacyImportLocalFile(
    runtime: S3SyncRepositoryContext,
    path: String,
    source: File,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode.Legacy,
) {
    if (legacyIsMemoPath(path, layout)) {
        runtime.markdownStorageDataSource.saveFileIn(
            directory = MemoDirectoryType.MAIN,
            filename = legacyExtractMemoFilename(path, layout),
            content = source.readText(StandardCharsets.UTF_8),
        )
        return
    }
    legacyDirectMediaFile(path, layout, mode)?.let { target ->
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return
    }
    runtime.localMediaSyncStore.importFromFile(path, source, layout)
}

internal suspend fun legacyWriteLocalBytes(
    runtime: S3SyncRepositoryContext,
    path: String,
    bytes: ByteArray,
    layout: SyncDirectoryLayout,
) {
    if (legacyIsMemoPath(path, layout)) {
        runtime.markdownStorageDataSource.saveFileIn(
            directory = MemoDirectoryType.MAIN,
            filename = legacyExtractMemoFilename(path, layout),
            content = String(bytes, StandardCharsets.UTF_8),
        )
    } else {
        runtime.localMediaSyncStore.writeBytes(path, bytes, layout)
    }
}

internal suspend fun legacyDeleteLocalFile(
    runtime: S3SyncRepositoryContext,
    path: String,
    layout: SyncDirectoryLayout,
) {
    if (legacyIsMemoPath(path, layout)) {
        runtime.markdownStorageDataSource.deleteFileIn(
            MemoDirectoryType.MAIN,
            legacyExtractMemoFilename(path, layout),
        )
    } else {
        runtime.localMediaSyncStore.delete(path, layout)
    }
}

private fun legacyDirectMediaFile(
    path: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode.Legacy,
): File? {
    val relativePath = path.removePrefix("$S3_ROOT/")
    val filename = relativePath.substringAfter('/', "")
    if (filename.isBlank()) {
        return null
    }
    val rootDirectory =
        when {
            relativePath.startsWith("${layout.imageFolder}/") -> mode.directImageRoot
            relativePath.startsWith("${layout.voiceFolder}/") -> mode.directVoiceRoot
            else -> null
        } ?: return null
    return File(rootDirectory, filename)
}

private fun String.transferSuffix(): String =
    substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" } ?: ".tmp"
