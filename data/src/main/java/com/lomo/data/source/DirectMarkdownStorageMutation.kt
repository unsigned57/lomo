package com.lomo.data.source

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal suspend fun directReadFile(
    rootDir: File,
    filename: String,
): String? =
    withContext(Dispatchers.IO) {
        val file = File(rootDir, filename)
        if (file.exists()) {
            file.readText()
        } else {
            null
        }
    }

internal suspend fun directReadTrashFile(
    rootDir: File,
    filename: String,
): String? =
    withContext(Dispatchers.IO) {
        val file = File(directTrashDir(rootDir), filename)
        if (file.exists()) {
            file.readText()
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
            file.readText()
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
        if (append) {
            file.appendText(content)
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
    val file = File(directTrashDir(rootDir), filename)
    if (append) {
        file.appendText(content)
    } else {
        directWriteTextAtomically(file, content)
    }
}

internal fun directWriteTextAtomically(
    target: File,
    content: String,
) {
    val parent = target.parentFile ?: throw IOException("Missing parent directory for ${target.absolutePath}")
    if (!parent.exists() && !parent.mkdirs()) {
        throw IOException("Failed to create parent directory ${parent.absolutePath}")
    }

    val temp = File(parent, "${target.name}.tmp.${System.nanoTime()}")
    temp.writeText(content)
    try {
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
    } finally {
        if (temp.exists()) {
            temp.delete()
        }
    }
}

internal suspend fun directDeleteFile(
    rootDir: File,
    filename: String,
) = withContext(Dispatchers.IO) {
    File(rootDir, filename).delete()
    Unit
}

internal suspend fun directDeleteTrashFile(
    rootDir: File,
    filename: String,
) = withContext(Dispatchers.IO) {
    File(directTrashDir(rootDir), filename).delete()
    Unit
}
