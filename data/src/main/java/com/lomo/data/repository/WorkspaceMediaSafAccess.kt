package com.lomo.data.repository

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.source.safIsImageFilename
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal suspend fun listWorkspaceSafFiles(
    context: Context,
    category: WorkspaceMediaCategory,
    rootUriString: String,
): List<WorkspaceMediaFile> =
    withContext(Dispatchers.IO) {
        resolveWorkspaceSafRoot(context, rootUriString)
            ?.listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && workspaceMatchesSafCategory(category, file) }
            ?.sortedBy { it.name.orEmpty() }
            ?.mapNotNull { file ->
                val filename = file.name ?: return@mapNotNull null
                val bytes =
                    context.contentResolver.openInputStream(file.uri)?.use { input ->
                        input.readBytes()
                    } ?: return@mapNotNull null
                WorkspaceMediaFile(filename = filename, bytes = bytes)
            }?.toList()
            ?: emptyList()
    }

internal suspend fun listWorkspaceSafFilenames(
    context: Context,
    category: WorkspaceMediaCategory,
    rootUriString: String,
): List<String> =
    withContext(Dispatchers.IO) {
        resolveWorkspaceSafRoot(context, rootUriString)
            ?.listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && workspaceMatchesSafCategory(category, file) }
            ?.mapNotNull(DocumentFile::getName)
            ?.sorted()
            ?.toList()
            ?: emptyList()
    }

internal suspend fun writeWorkspaceSafFile(
    context: Context,
    category: WorkspaceMediaCategory,
    rootUriString: String,
    filename: String,
    bytes: ByteArray,
) {
    withContext(Dispatchers.IO) {
        val root = requireNotNull(resolveWorkspaceSafRoot(context, rootUriString)) { "Cannot access SAF media root" }
        root.findFile(filename)?.delete()
        val target =
            root.createFile(workspaceMimeTypeFor(category, filename), filename)
                ?: throw IOException("Cannot create SAF media file: $filename")
        context.contentResolver.openOutputStream(target.uri)?.use { output ->
            output.write(bytes)
        } ?: throw IOException("Cannot open SAF media output stream: $filename")
    }
}

internal suspend fun deleteWorkspaceSafFile(
    context: Context,
    rootUriString: String,
    filename: String,
) {
    withContext(Dispatchers.IO) {
        resolveWorkspaceSafRoot(context, rootUriString)?.findFile(filename)?.delete()
    }
}

internal fun isContentUriRoot(value: String): Boolean =
    runCatching {
        java.net.URI(value).scheme.equals("content", ignoreCase = true)
    }.getOrDefault(false)

private fun resolveWorkspaceSafRoot(
    context: Context,
    rootUriString: String,
): DocumentFile? = runCatching { DocumentFile.fromTreeUri(context, rootUriString.toUri()) }.getOrNull()

private fun workspaceMatchesSafCategory(
    category: WorkspaceMediaCategory,
    file: DocumentFile,
): Boolean {
    val filename = file.name ?: return false
    return when (category) {
        WorkspaceMediaCategory.IMAGE ->
            file.type?.startsWith(IMAGE_MIME_PREFIX) == true || safIsImageFilename(filename)
        WorkspaceMediaCategory.VOICE ->
            file.type?.startsWith(AUDIO_MIME_PREFIX) == true || isWorkspaceSafAudioFilename(filename)
    }
}

private fun workspaceMimeTypeFor(
    category: WorkspaceMediaCategory,
    filename: String,
): String =
    when (category) {
        WorkspaceMediaCategory.IMAGE -> workspaceImageMimeType(filename)
        WorkspaceMediaCategory.VOICE -> workspaceAudioMimeType(filename)
    }

private fun workspaceImageMimeType(filename: String): String =
    when (filename.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        else -> "image/jpeg"
    }

private fun workspaceAudioMimeType(filename: String): String =
    when (filename.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/mp4"
    }

private fun isWorkspaceSafAudioFilename(name: String): Boolean {
    val extension = name.substringAfterLast('.', "")
    return extension.isNotBlank() && extension.lowercase(java.util.Locale.ROOT) in SAF_AUDIO_EXTENSIONS
}

private const val AUDIO_MIME_PREFIX = "audio/"
private const val IMAGE_MIME_PREFIX = "image/"
private val SAF_AUDIO_EXTENSIONS = setOf("m4a", "mp3", "aac", "ogg", "wav")
