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
                LocalS3File(path, metadata.lastModified)
            }
    return memoFiles + mediaFiles
}

internal suspend fun listVaultRootLocalFiles(
    mode: S3LocalSyncMode.VaultRoot,
    safTreeAccess: S3SafTreeAccess,
): Map<String, LocalS3File> =
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot -> listFileVaultRootLocalFiles(mode)
        is S3LocalSyncMode.SafVaultRoot ->
            safTreeAccess
                .listFiles(mode.rootUriString)
                .mapNotNull { file ->
                    file.relativePath
                        .takeIf(::isSyncableContentPath)
                        ?.let { relativePath ->
                            relativePath to LocalS3File(relativePath, file.lastModified)
                        }
                }.toMap()
    }

internal suspend fun readVaultRootBytes(
    mode: S3LocalSyncMode.VaultRoot,
    relativePath: String,
    safTreeAccess: S3SafTreeAccess,
): ByteArray? =
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot -> readFileVaultRootBytes(mode, relativePath)
        is S3LocalSyncMode.SafVaultRoot -> safTreeAccess.readBytes(mode.rootUriString, relativePath)
    }

internal suspend fun readVaultRootText(
    mode: S3LocalSyncMode.VaultRoot,
    relativePath: String,
    safTreeAccess: S3SafTreeAccess,
): String? =
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot -> readFileVaultRootText(mode, relativePath)
        is S3LocalSyncMode.SafVaultRoot -> safTreeAccess.readText(mode.rootUriString, relativePath)
    }

internal suspend fun writeVaultRootBytes(
    mode: S3LocalSyncMode.VaultRoot,
    relativePath: String,
    bytes: ByteArray,
    safTreeAccess: S3SafTreeAccess,
) {
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot -> writeFileVaultRootBytes(mode, relativePath, bytes)
        is S3LocalSyncMode.SafVaultRoot -> safTreeAccess.writeBytes(mode.rootUriString, relativePath, bytes)
    }
}

internal suspend fun deleteVaultRootFile(
    mode: S3LocalSyncMode.VaultRoot,
    relativePath: String,
    safTreeAccess: S3SafTreeAccess,
) {
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot -> deleteFileVaultRootFile(mode, relativePath)
        is S3LocalSyncMode.SafVaultRoot -> safTreeAccess.deleteFile(mode.rootUriString, relativePath)
    }
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
                    LocalS3File(path = path, lastModified = metadata.lastModified)
                }
    }

private fun legacyDirectMediaLocalFile(
    path: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode.Legacy,
): LocalS3File? {
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
    val targetFile = File(rootDirectory, filename)
    return if (targetFile.exists() && targetFile.isFile) {
        LocalS3File(path = path, lastModified = targetFile.lastModified())
    } else {
        null
    }
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
