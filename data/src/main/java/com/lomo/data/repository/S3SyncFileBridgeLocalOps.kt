package com.lomo.data.repository

import com.lomo.data.source.directWriteTextAtomically
import java.io.File
import java.nio.charset.StandardCharsets

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
                            relativePath to LocalS3File(relativePath, file.lastModified, file.size)
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

internal suspend fun exportVaultRootFile(
    mode: S3LocalSyncMode.VaultRoot,
    relativePath: String,
    safTreeAccess: S3SafTreeAccess,
    session: S3SyncTransferSession,
): S3TransferFile? =
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot -> {
            val file = File(mode.rootDir, relativePath)
            file.takeIf { it.exists() && it.isFile }?.let(::S3TransferFile)
        }

        is S3LocalSyncMode.SafVaultRoot -> {
            val tempFile = session.createTempFile("s3-saf-export-", relativePath.transferSuffix())
            if (safTreeAccess.exportToFile(mode.rootUriString, relativePath, tempFile)) {
                S3TransferFile(tempFile)
            } else {
                null
            }
        }
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

internal suspend fun importVaultRootFile(
    mode: S3LocalSyncMode.VaultRoot,
    relativePath: String,
    source: File,
    safTreeAccess: S3SafTreeAccess,
) {
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot -> {
            val target = File(mode.rootDir, relativePath)
            target.parentFile?.mkdirs()
            if (relativePath.endsWith(S3_MEMO_SUFFIX)) {
                directWriteTextAtomically(target, source.readText(StandardCharsets.UTF_8))
            } else {
                source.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        is S3LocalSyncMode.SafVaultRoot ->
            safTreeAccess.importFromFile(mode.rootUriString, relativePath, source)
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

private fun String.transferSuffix(): String =
    substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" } ?: ".tmp"
