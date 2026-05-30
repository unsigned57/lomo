package com.lomo.data.repository

import com.lomo.data.source.directWriteTextAtomically
import com.lomo.data.source.ensureWithinDirectory
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
                file == mode.rootDir || !file.name.startsWith(".") && file.isInsideVaultRoot(mode)
            }.filter(File::isFile)
            .mapNotNull { file ->
                val relativePath = relativePathFrom(mode.rootDir, file) ?: return@mapNotNull null
                val vaultPath = VaultRootPath.from(relativePath) ?: return@mapNotNull null
                if (!isSyncableContentPath(vaultPath.value) || !file.isInsideVaultRoot(mode)) {
                    return@mapNotNull null
                }
                vaultPath.value to LocalS3File(vaultPath.value, file.lastModified(), file.length())
            }.toMap()
    }

internal suspend fun readFileVaultRootBytes(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: VaultRootPath,
): ByteArray? =
    withContext(Dispatchers.IO) {
        val file = mode.resolveVaultRootFile(relativePath)
        if (file.exists() && file.isFile) file.readBytes() else null
    }

internal suspend fun readFileVaultRootText(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: VaultRootPath,
): String? =
    withContext(Dispatchers.IO) {
        val file = mode.resolveVaultRootFile(relativePath)
        if (file.exists() && file.isFile) file.readText(StandardCharsets.UTF_8) else null
    }

internal suspend fun getFileVaultRootLocalFile(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: VaultRootPath,
): LocalS3File? =
    withContext(Dispatchers.IO) {
        val file = mode.resolveVaultRootFile(relativePath)
        if (file.exists() && file.isFile) {
            LocalS3File(path = relativePath.value, lastModified = file.lastModified(), size = file.length())
        } else {
            null
        }
    }

internal suspend fun writeFileVaultRootBytes(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: VaultRootPath,
    bytes: ByteArray,
) {
    withContext(Dispatchers.IO) {
        mode.resolveVaultRootFile(relativePath).parentFile?.mkdirs()
        val file = mode.resolveVaultRootFile(relativePath)
        if (relativePath.value.endsWith(S3_MEMO_SUFFIX)) {
            directWriteTextAtomically(file, String(bytes, StandardCharsets.UTF_8))
        } else {
            file.writeBytes(bytes)
        }
    }
}

internal suspend fun deleteFileVaultRootFile(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: VaultRootPath,
) {
    withContext(Dispatchers.IO) {
        mode.resolveVaultRootFile(relativePath).delete()
    }
}

internal fun S3LocalSyncMode.FileVaultRoot.resolveVaultRootFile(relativePath: VaultRootPath): File =
    File(rootDir, relativePath.value).also { target ->
        ensureWithinDirectory(rootDir, target)
    }

private fun File.isInsideVaultRoot(mode: S3LocalSyncMode.FileVaultRoot): Boolean =
    // behavior-contract: silent-result-ok: ensureWithinDirectory throws outside-root; false is the containment answer
    runCatching {
        ensureWithinDirectory(mode.rootDir, this)
        true
    }.getOrDefault(false)
