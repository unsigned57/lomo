package com.lomo.data.repository

import com.lomo.data.source.directEnsureRootExists
import com.lomo.data.source.directIsImageFilename
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal suspend fun listWorkspaceDirectFiles(
    category: WorkspaceMediaCategory,
    root: File,
): List<WorkspaceMediaFile> =
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
                WorkspaceMediaFile(
                    filename = file.name,
                    bytes = file.readBytes(),
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

internal suspend fun writeWorkspaceDirectFile(
    root: File,
    filename: String,
    bytes: ByteArray,
) {
    withContext(Dispatchers.IO) {
        directEnsureRootExists(root)
        File(root, filename).writeBytes(bytes)
    }
}

internal suspend fun deleteWorkspaceDirectFile(
    root: File,
    filename: String,
) {
    withContext(Dispatchers.IO) {
        val target = File(root, filename)
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
    val extension = name.substringAfterLast('.', "")
    return extension.isNotBlank() && extension.lowercase(java.util.Locale.ROOT) in DIRECT_AUDIO_EXTENSIONS
}

private val DIRECT_AUDIO_EXTENSIONS = setOf("m4a", "mp3", "aac", "ogg", "wav")
