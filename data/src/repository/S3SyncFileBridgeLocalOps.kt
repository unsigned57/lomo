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
                    VaultRootPath.from(file.relativePath)
                        ?.takeIf { path -> mode.acceptsWorkspacePath(path.value) }
                        ?.let { path ->
                            path.value to LocalS3File(path.value, file.lastModified, file.size)
                        }
                }.toMap()
    }

internal suspend fun getVaultRootLocalAuditPage(
    mode: S3LocalSyncMode.VaultRoot,
    safTreeAccess: S3SafTreeAccess,
    afterRelativePath: String?,
    limit: Int,
): List<LocalS3File> =
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot ->
            getFileVaultRootLocalAuditPage(
                mode = mode,
                afterRelativePath = afterRelativePath,
                limit = limit,
            )

        is S3LocalSyncMode.SafVaultRoot ->
            safTreeAccess
                .listFiles(mode.rootUriString)
                .mapNotNull { file ->
                    VaultRootPath.from(file.relativePath)
                        ?.takeIf { path -> mode.acceptsWorkspacePath(path.value) }
                        ?.let { path -> LocalS3File(path.value, file.lastModified, file.size) }
                }.filter { file -> afterRelativePath == null || file.path > afterRelativePath }
                .sortedBy(LocalS3File::path)
                .take(limit)
    }

internal suspend fun readVaultRootBytes(
    mode: S3LocalSyncMode.VaultRoot,
    relativePath: VaultRootPath,
    safTreeAccess: S3SafTreeAccess,
): ByteArray? =
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot -> readFileVaultRootBytes(mode, relativePath)
        is S3LocalSyncMode.SafVaultRoot -> safTreeAccess.readBytes(mode.rootUriString, relativePath.value)
    }

internal suspend fun readVaultRootText(
    mode: S3LocalSyncMode.VaultRoot,
    relativePath: VaultRootPath,
    safTreeAccess: S3SafTreeAccess,
): String? =
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot -> readFileVaultRootText(mode, relativePath)
        is S3LocalSyncMode.SafVaultRoot -> safTreeAccess.readText(mode.rootUriString, relativePath.value)
    }

internal suspend fun exportVaultRootFile(
    mode: S3LocalSyncMode.VaultRoot,
    relativePath: VaultRootPath,
    safTreeAccess: S3SafTreeAccess,
    session: S3SyncTransferSession,
): S3TransferFile? =
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot -> {
            val file = mode.resolveVaultRootFile(relativePath)
            file.takeIf { it.exists() && it.isFile }?.let(::S3TransferFile)
        }

        is S3LocalSyncMode.SafVaultRoot -> {
            val tempFile = session.createTempFile("s3-saf-export-", relativePath.value.transferSuffix())
            if (safTreeAccess.exportToFile(mode.rootUriString, relativePath.value, tempFile)) {
                S3TransferFile(tempFile)
            } else {
                null
            }
        }
    }

internal suspend fun writeVaultRootBytes(
    mode: S3LocalSyncMode.VaultRoot,
    relativePath: VaultRootPath,
    bytes: ByteArray,
    safTreeAccess: S3SafTreeAccess,
) {
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot -> writeFileVaultRootBytes(mode, relativePath, bytes)
        is S3LocalSyncMode.SafVaultRoot -> safTreeAccess.writeBytes(mode.rootUriString, relativePath.value, bytes)
    }
}

internal suspend fun importVaultRootFile(
    mode: S3LocalSyncMode.VaultRoot,
    relativePath: VaultRootPath,
    source: File,
    safTreeAccess: S3SafTreeAccess,
) {
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot -> {
            mode.resolveVaultRootFile(relativePath).parentFile?.mkdirs()
            val target = mode.resolveVaultRootFile(relativePath)
            if (relativePath.value.endsWith(S3_MEMO_SUFFIX)) {
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
            safTreeAccess.importFromFile(mode.rootUriString, relativePath.value, source)
    }
}

internal suspend fun deleteVaultRootFile(
    mode: S3LocalSyncMode.VaultRoot,
    relativePath: VaultRootPath,
    safTreeAccess: S3SafTreeAccess,
) {
    when (mode) {
        is S3LocalSyncMode.FileVaultRoot -> deleteFileVaultRootFile(mode, relativePath)
        is S3LocalSyncMode.SafVaultRoot -> safTreeAccess.deleteFile(mode.rootUriString, relativePath.value)
    }
}

private fun String.transferSuffix(): String =
    substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" } ?: ".tmp"
