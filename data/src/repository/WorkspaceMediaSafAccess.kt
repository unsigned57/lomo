package com.lomo.data.repository

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

internal suspend fun listWorkspaceSafFiles(
    context: Context,
    category: WorkspaceMediaCategory,
    rootUriString: String,
): List<WorkspaceMediaDescriptor> =
    withContext(Dispatchers.IO) {
        resolveWorkspaceSafRoot(context, rootUriString)
            ?.listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && workspaceMatchesSafCategory(category, file) }
            ?.sortedBy { it.name.orEmpty() }
            ?.mapNotNull { file ->
                val filename = file.name ?: return@mapNotNull null
                WorkspaceMediaDescriptor(
                    filename = filename,
                    sizeBytes = file.length(),
                )
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

internal suspend fun readWorkspaceSafFileToStream(
    context: Context,
    category: WorkspaceMediaCategory,
    rootUriString: String,
    filename: String,
    destination: OutputStream,
): Boolean =
    withContext(Dispatchers.IO) {
        val target = resolveWorkspaceSafRoot(context, rootUriString)
            ?.findFile(filename)
            ?.takeIf { file -> file.isFile && file.name == filename && workspaceMatchesSafCategory(category, file) }
            ?: return@withContext false
        context.contentResolver.openInputStream(target.uri)?.use { input ->
            input.copyTo(destination, bufferSize = WORKSPACE_MEDIA_STREAM_BUFFER_BYTES)
        } ?: return@withContext false
        true
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
    // behavior-contract: silent-result-ok: malformed URI → false (not a content URI)
    runCatching {
        java.net.URI(value).scheme.equals("content", ignoreCase = true)
    }.getOrDefault(false)

internal fun resolveWorkspaceSafRoot(
    context: Context,
    rootUriString: String,
    // behavior-contract: silent-result-ok: revoked SAF → null; workspace root unavailable
): DocumentFile? = runCatching { DocumentFile.fromTreeUri(context, rootUriString.toUri()) }.getOrNull()

internal fun temporaryWorkspaceSafFilename(filename: String): String =
    "$filename.tmp.${UUID.randomUUID()}"

private const val WORKSPACE_MEDIA_STREAM_BUFFER_BYTES = 64 * 1024
