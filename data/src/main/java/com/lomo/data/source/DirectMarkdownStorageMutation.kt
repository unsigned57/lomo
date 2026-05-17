package com.lomo.data.source

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal suspend fun directReadFile(
    rootDir: File,
    filename: String,
): String? =
    withContext(Dispatchers.IO) {
        val file = File(rootDir, filename)
        ensureWithinDirectory(rootDir, file)
        if (file.exists()) {
            file.readTextBestEffortUtf8()
        } else {
            null
        }
    }

internal suspend fun directReadTrashFile(
    rootDir: File,
    filename: String,
): String? =
    withContext(Dispatchers.IO) {
        val trashDir = directTrashDir(rootDir)
        val file = File(trashDir, filename)
        ensureWithinDirectory(trashDir, file)
        if (file.exists()) {
            file.readTextBestEffortUtf8()
        } else {
            null
        }
    }

internal suspend fun directReadFileUri(uri: Uri): String? =
    withContext(Dispatchers.IO) {
        if (uri.scheme != "file") {
            return@withContext null
        }
        val path = uri.path ?: return@withContext null
        val file = File(path)
        if (file.exists()) {
            file.readTextBestEffortUtf8()
        } else {
            null
        }
    }

internal suspend fun directSaveFile(
    rootDir: File,
    filename: String,
    content: String,
    append: Boolean,
): String? =
    withContext(Dispatchers.IO) {
        directEnsureRootExists(rootDir)
        val file = File(rootDir, filename)
        ensureWithinDirectory(rootDir, file)
        if (append) {
            appendTextDurably(file, content)
        } else {
            directWriteTextAtomically(file, content)
        }
        file.absolutePath
    }

internal suspend fun directSaveTrashFile(
    rootDir: File,
    filename: String,
    content: String,
    append: Boolean,
) = withContext(Dispatchers.IO) {
    directEnsureTrashExists(rootDir)
    val trashDir = directTrashDir(rootDir)
    val file = File(trashDir, filename)
    ensureWithinDirectory(trashDir, file)
    if (append) {
        appendTextDurably(file, content)
    } else {
        directWriteTextAtomically(file, content)
    }
}

/**
 * Append that fsyncs the file descriptor before returning. Guarantees the bytes survive
 * a subsequent power loss; readers at worst see the pre-append state, never a torn line.
 */
internal fun appendTextDurably(
    file: File,
    content: String,
) {
    val parent = file.parentFile
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
        throw IOException("Failed to create parent directory ${parent.absolutePath}")
    }
    FileOutputStream(file, true).use { fos ->
        fos.write(content.toByteArray(Charsets.UTF_8))
        fos.fd.sync()
    }
}

/**
 * Atomic rewrite: stage content to a temp file, fsync it, then rename onto [target].
 * A crash at any point leaves the filesystem with either the old or the new [target],
 * never a partial mix. Parent directory is fsynced best-effort so the rename itself
 * is durable across power loss.
 */
internal fun directWriteTextAtomically(
    target: File,
    content: String,
) {
    val parent = target.parentFile ?: throw IOException("Missing parent directory for ${target.absolutePath}")
    if (!parent.exists() && !parent.mkdirs()) {
        throw IOException("Failed to create parent directory ${parent.absolutePath}")
    }

    val temp = File(parent, "${target.name}.tmp.${UUID.randomUUID()}")
    try {
        FileOutputStream(temp).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        fsyncDirectoryBestEffort(parent)
    } finally {
        if (temp.exists()) {
            temp.delete()
        }
    }
}

internal suspend fun directDeleteFile(
    rootDir: File,
    filename: String,
    secureWipe: (File) -> Unit = ::secureWipeFileBeforeDelete,
) = withContext(Dispatchers.IO) {
    val file = File(rootDir, filename)
    ensureWithinDirectory(rootDir, file)
    if (file.exists()) {
        secureWipe(file)
    }
    file.delete()
    Unit
}

internal suspend fun directDeleteTrashFile(
    rootDir: File,
    filename: String,
    secureWipe: (File) -> Unit = ::secureWipeFileBeforeDelete,
) = withContext(Dispatchers.IO) {
    val trashDir = directTrashDir(rootDir)
    val file = File(trashDir, filename)
    ensureWithinDirectory(trashDir, file)
    if (file.exists()) {
        secureWipe(file)
    }
    file.delete()
    Unit
}

private const val SECURE_WIPE_CHUNK_SIZE_BYTES = 8 * 1024

internal fun secureWipeFileBeforeDelete(file: File) {
    if (!file.exists() || !file.isFile) {
        return
    }
    RandomAccessFile(file, "rwd").use { randomAccessFile ->
        val originalLength = randomAccessFile.length()
        if (originalLength <= 0L) {
            return
        }
        val wipeBuffer = ByteArray(SECURE_WIPE_CHUNK_SIZE_BYTES)
        var remainingBytes = originalLength
        randomAccessFile.seek(0L)
        while (remainingBytes > 0L) {
            val bytesToWrite = minOf(wipeBuffer.size.toLong(), remainingBytes).toInt()
            randomAccessFile.write(wipeBuffer, 0, bytesToWrite)
            remainingBytes -= bytesToWrite
        }
        randomAccessFile.fd.sync()
    }
}
