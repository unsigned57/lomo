package com.lomo.data.source

import android.system.Os
import android.system.OsConstants
import com.lomo.domain.model.MediaFileExtensions
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

internal fun directTrashDir(rootDir: File): File = File(rootDir, DIRECT_TRASH_DIR_NAME)

internal fun directEnsureRootExists(rootDir: File) {
    if (!rootDir.exists()) {
        rootDir.mkdirs()
    }
}

internal fun directEnsureTrashExists(rootDir: File) {
    val trashDir = directTrashDir(rootDir)
    if (!trashDir.exists()) {
        trashDir.mkdirs()
    }
}

internal fun directIsImageFilename(name: String): Boolean {
    return MediaFileExtensions.hasImageExtension(name)
}

/**
 * Rejects writes/reads whose canonical path escapes the workspace root.
 * Defense in depth against tainted memo dateKeys arriving from remote sync.
 */
internal fun ensureWithinDirectory(
    root: File,
    target: File,
) {
    val rootPath = root.canonicalFile.toPath().normalize()
    val targetPath = target.canonicalFile.toPath().normalize()
    if (!targetPath.startsWith(rootPath)) {
        throw SecurityException("Path escapes workspace root: ${target.absolutePath}")
    }
}

/**
 * Best-effort fsync on the directory entry so a preceding atomic rename is durable.
 * No-op if the platform rejects directory fds or if Os is unavailable (e.g. JVM unit tests,
 * where android.system.Os methods throw stub RuntimeExceptions).
 */
internal fun fsyncDirectoryBestEffort(dir: File) {
    val fd =
        runCatching {
            Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
        }.onFailure { error ->
            // Covers ErrnoException on real Android and the stubbed RuntimeException("Stub!")
            // that android.system.Os throws under JVM unit tests.
            Timber.v(error, "Directory fsync unavailable: %s", dir.absolutePath)
        }.getOrNull() ?: return

    try {
        runCatching { Os.fsync(fd) }.onFailure { error ->
            Timber.v(error, "Directory fsync failed: %s", dir.absolutePath)
        }
    } finally {
        runCatching { Os.close(fd) }
    }
}

/**
 * UTF-8 decode that replaces malformed bytes instead of throwing. Keeps the parser usable when
 * an external sync drops a file whose encoding drifted (e.g. Obsidian export with BOM).
 */
internal fun File.readTextBestEffortUtf8(): String {
    return try {
        readText(Charsets.UTF_8)
    } catch (error: IOException) {
        Timber.w(error, "UTF-8 decode failed, replacing malformed bytes: %s", name)
        FileInputStream(this).use { stream ->
            val decoder =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
            InputStreamReader(stream, decoder).readText()
        }
    }
}

internal const val DIRECT_MARKDOWN_SUFFIX = ".md"
private const val DIRECT_TRASH_DIR_NAME = ".trash"
