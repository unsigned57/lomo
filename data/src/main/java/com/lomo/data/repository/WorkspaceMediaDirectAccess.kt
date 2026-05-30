package com.lomo.data.repository

import com.lomo.data.source.directEnsureRootExists
import com.lomo.data.source.directIsImageFilename
import com.lomo.data.source.ensureWithinDirectory
import com.lomo.data.source.fsyncDirectoryBestEffort
import com.lomo.domain.model.MediaFileExtensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal suspend fun listWorkspaceDirectFiles(
    category: WorkspaceMediaCategory,
    root: File,
): List<WorkspaceMediaDescriptor> =
    withContext(Dispatchers.IO) {
        if (!root.exists() || !root.isDirectory) {
            return@withContext emptyList()
        }
        root
            .listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && workspaceMatchesDirectCategory(category, file.name) }
            ?.sortedBy { it.name }
            ?.map { file ->
                WorkspaceMediaDescriptor(
                    filename = file.name,
                    sizeBytes = file.length(),
                )
            }?.toList()
            ?: emptyList()
    }

internal suspend fun listWorkspaceDirectFilenames(
    category: WorkspaceMediaCategory,
    root: File,
): List<String> =
    withContext(Dispatchers.IO) {
        if (!root.exists() || !root.isDirectory) {
            return@withContext emptyList()
        }
        root
            .listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && workspaceMatchesDirectCategory(category, file.name) }
            ?.map { it.name }
            ?.sorted()
            ?.toList()
            ?: emptyList()
    }

internal suspend fun readWorkspaceDirectFileToStream(
    category: WorkspaceMediaCategory,
    root: File,
    filename: String,
    destination: OutputStream,
): Boolean =
    withContext(Dispatchers.IO) {
        if (!root.exists() || !root.isDirectory) {
            return@withContext false
        }
        val target = workspaceDirectTarget(root, filename)
        if (!target.isFile || !workspaceMatchesDirectCategory(category, target.name)) {
            return@withContext false
        }
        target.inputStream().use { input ->
            input.copyTo(destination, bufferSize = WORKSPACE_MEDIA_STREAM_BUFFER_BYTES)
        }
        true
    }

internal suspend fun writeWorkspaceDirectFileFromStream(
    root: File,
    filename: String,
    source: suspend (OutputStream) -> Unit,
) {
    withContext(Dispatchers.IO) {
        directEnsureRootExists(root)
        val target = workspaceDirectTarget(root, filename)
        writeWorkspaceDirectFileAtomically(target = target, source = source)
    }
}

internal suspend fun deleteWorkspaceDirectFile(
    root: File,
    filename: String,
) {
    withContext(Dispatchers.IO) {
        val target = workspaceDirectTarget(root, filename)
        if (target.exists()) {
            target.delete()
        }
    }
}

private fun workspaceMatchesDirectCategory(
    category: WorkspaceMediaCategory,
    filename: String,
): Boolean =
    when (category) {
        WorkspaceMediaCategory.IMAGE -> directIsImageFilename(filename)
        WorkspaceMediaCategory.VOICE -> isWorkspaceAudioFilename(filename)
    }

private fun isWorkspaceAudioFilename(name: String): Boolean {
    return MediaFileExtensions.hasAudioExtension(name)
}

private fun workspaceDirectTarget(
    root: File,
    filename: String,
): File {
    val target = File(root, filename)
    ensureWithinDirectory(root, target)
    return target
}

private suspend fun writeWorkspaceDirectFileAtomically(
    target: File,
    source: suspend (OutputStream) -> Unit,
) {
    val parent = target.parentFile ?: throw IOException("Missing parent directory for ${target.absolutePath}")
    if (!parent.exists() && !parent.mkdirs()) {
        throw IOException("Failed to create parent directory ${parent.absolutePath}")
    }
    val temp = File(parent, "${target.name}.tmp.${UUID.randomUUID()}")
    try {
        FileOutputStream(temp).use { output ->
            source(output)
            output.fd.sync()
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

private const val WORKSPACE_MEDIA_STREAM_BUFFER_BYTES = 64 * 1024
