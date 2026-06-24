package com.lomo.data.repository

import com.lomo.data.source.ensureWithinDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
                if (!mode.acceptsWorkspacePath(vaultPath.value) || !file.isInsideVaultRoot(mode)) {
                    return@mapNotNull null
                }
                vaultPath.value to
                    LocalS3File(
                        path = vaultPath.value,
                        lastModified = file.lastModified(),
                        size = file.length(),
                    )
            }.toMap()
    }

internal suspend fun getFileVaultRootLocalAuditPage(
    mode: S3LocalSyncMode.FileVaultRoot,
    afterRelativePath: String?,
    limit: Int,
): List<LocalS3File> =
    withContext(Dispatchers.IO) {
        if (!mode.rootDir.exists() || !mode.rootDir.isDirectory) {
            return@withContext emptyList()
        }
        val page = mutableListOf<LocalS3File>()
        collectStableFileVaultRootLocalAuditPage(
            mode = mode,
            directory = mode.rootDir,
            afterRelativePath = afterRelativePath,
            limit = limit,
            page = page,
        )
        page
    }

private fun collectStableFileVaultRootLocalAuditPage(
    mode: S3LocalSyncMode.FileVaultRoot,
    directory: File,
    afterRelativePath: String?,
    limit: Int,
    page: MutableList<LocalS3File>,
): Boolean {
    val children =
        directory
            .listFiles()
            ?.mapNotNull { child -> child.auditTraversalKey(mode)?.let { key -> key to child } }
            ?.sortedBy { (key, _) -> key }
            ?.map { (_, child) -> child }
            ?: return true
    children.forEach { child ->
        if (child.name.startsWith(".") || !child.isInsideVaultRoot(mode)) {
            return@forEach
        }
        when {
            child.isDirectory -> {
                val keepGoing =
                    collectStableFileVaultRootLocalAuditPage(
                        mode = mode,
                        directory = child,
                        afterRelativePath = afterRelativePath,
                        limit = limit,
                        page = page,
                    )
                if (!keepGoing) {
                    return false
                }
            }

            child.isFile -> {
                val localFile = child.toFileVaultRootAuditCandidate(mode) ?: return@forEach
                if (afterRelativePath == null || localFile.path > afterRelativePath) {
                    page += localFile
                    if (page.size >= limit) {
                        return false
                    }
                }
            }
        }
    }
    return true
}

private fun File.auditTraversalKey(mode: S3LocalSyncMode.FileVaultRoot): String? {
    if (name.startsWith(".") || !isInsideVaultRoot(mode)) {
        return null
    }
    val relativePath = relativePathFrom(mode.rootDir, this) ?: return null
    return if (isDirectory) "$relativePath/" else relativePath
}

private fun File.toFileVaultRootAuditCandidate(mode: S3LocalSyncMode.FileVaultRoot): LocalS3File? {
    val relativePath = relativePathFrom(mode.rootDir, this) ?: return null
    val vaultPath = VaultRootPath.from(relativePath) ?: return null
    if (!mode.acceptsWorkspacePath(vaultPath.value) || !isInsideVaultRoot(mode)) {
        return null
    }
    return LocalS3File(
        path = vaultPath.value,
        lastModified = lastModified(),
        size = length(),
    )
}

internal suspend fun readFileVaultRootBytes(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: VaultRootPath,
): ByteArray? =
    withContext(Dispatchers.IO) {
        val file = mode.resolveVaultRootFile(relativePath)
        if (file.exists() && file.isFile) file.readBytes() else null
    }

internal suspend fun getFileVaultRootLocalFile(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: VaultRootPath,
): LocalS3File? =
    withContext(Dispatchers.IO) {
        val file = mode.resolveVaultRootFile(relativePath)
        if (file.exists() && file.isFile) {
            LocalS3File(
                path = relativePath.value,
                lastModified = file.lastModified(),
                size = file.length(),
            )
        } else {
            null
        }
    }

internal suspend fun computeFileVaultRootFingerprint(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: VaultRootPath,
): String? =
    withContext(Dispatchers.IO) {
        val file = mode.resolveVaultRootFile(relativePath)
        if (file.exists() && file.isFile) file.md5Hex() else null
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
