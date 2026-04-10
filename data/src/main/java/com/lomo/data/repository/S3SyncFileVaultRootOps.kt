package com.lomo.data.repository

import com.lomo.data.source.directWriteTextAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

internal suspend fun listFileVaultRootLocalFiles(
    mode: S3LocalSyncMode.FileVaultRoot,
): Map<String, LocalS3File> =
    withContext(Dispatchers.IO) {
        if (!mode.rootDir.exists() || !mode.rootDir.isDirectory) {
            return@withContext emptyMap()
        }
        mode.rootDir
            .walkTopDown()
            .onEnter { file ->
                file == mode.rootDir || !file.name.startsWith(".")
            }.filter(File::isFile)
            .mapNotNull { file ->
                val relativePath = relativePathFrom(mode.rootDir, file) ?: return@mapNotNull null
                if (!isSyncableContentPath(relativePath)) {
                    return@mapNotNull null
                }
                relativePath to LocalS3File(relativePath, file.lastModified(), file.length())
            }.toMap()
    }

internal suspend fun readFileVaultRootBytes(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: String,
): ByteArray? =
    withContext(Dispatchers.IO) {
        val file = File(mode.rootDir, relativePath)
        if (file.exists() && file.isFile) file.readBytes() else null
    }

internal suspend fun readFileVaultRootText(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: String,
): String? =
    withContext(Dispatchers.IO) {
        val file = File(mode.rootDir, relativePath)
        if (file.exists() && file.isFile) file.readText(StandardCharsets.UTF_8) else null
    }

internal suspend fun getFileVaultRootLocalFile(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: String,
): LocalS3File? =
    withContext(Dispatchers.IO) {
        val file = File(mode.rootDir, relativePath)
        if (file.exists() && file.isFile) {
            LocalS3File(path = relativePath, lastModified = file.lastModified(), size = file.length())
        } else {
            null
        }
    }

internal suspend fun writeFileVaultRootBytes(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: String,
    bytes: ByteArray,
) {
    withContext(Dispatchers.IO) {
        val file = File(mode.rootDir, relativePath)
        file.parentFile?.mkdirs()
        if (relativePath.endsWith(S3_MEMO_SUFFIX)) {
            directWriteTextAtomically(file, String(bytes, StandardCharsets.UTF_8))
        } else {
            file.writeBytes(bytes)
        }
    }
}

internal suspend fun deleteFileVaultRootFile(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: String,
) {
    withContext(Dispatchers.IO) {
        File(mode.rootDir, relativePath).delete()
    }
}
