package com.lomo.data.repository

import com.lomo.data.source.directWriteTextAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

internal suspend fun readFileVaultRootText(
    mode: S3LocalSyncMode.FileVaultRoot,
    relativePath: VaultRootPath,
): String? =
    withContext(Dispatchers.IO) {
        val file = mode.resolveVaultRootFile(relativePath)
        if (file.exists() && file.isFile) file.readText(StandardCharsets.UTF_8) else null
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
