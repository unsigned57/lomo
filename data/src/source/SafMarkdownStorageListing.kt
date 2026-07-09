package com.lomo.data.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.data.util.runNonFatalCatching
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import timber.log.Timber

internal suspend fun safListMetadata(
    context: Context,
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
): List<FileMetadata> =
    withContext(SAF_IO_DISPATCHER) {
        // behavior-contract: silent-result-ok: fast recursive query may fail; falls to safListMetadataSlow
        runCatching { safQueryMainMarkdownMetadataRecursive(context, rootUri) }
            .getOrNull()
            ?.takeIf(List<FileMetadata>::isNotEmpty)
            ?: safListMetadataSlow(documentAccess)
    }

internal fun safListMetadataSlow(documentAccess: SafDocumentAccess): List<FileMetadata> =
    safWalkMainMarkdownFiles(documentAccess).map { (file, relativePath) ->
        FileMetadata(
            filename = relativePath,
            lastModified = file.lastModified(),
            size = file.length(),
        )
    }

internal suspend fun safListTrashMetadata(
    documentAccess: SafDocumentAccess,
): List<FileMetadata> =
    withContext(SAF_IO_DISPATCHER) {
        val trashDir = documentAccess.trashDir() ?: return@withContext emptyList()
        trashDir.listFiles().mapNotNull { file ->
            val name = file.name
            if (safMatchesMarkdownTarget(name, targetFilename = null)) {
                FileMetadata(
                    filename = name ?: return@mapNotNull null,
                    lastModified = file.lastModified(),
                    size = file.length(),
                )
            } else {
                null
            }
        }
    }

internal suspend fun safListMetadataWithIds(
    context: Context,
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
): List<FileMetadataWithId> =
    safStreamMetadataWithIds(context, rootUri, documentAccess).toList()

internal suspend fun safListTrashMetadataWithIds(
    context: Context,
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
): List<FileMetadataWithId> =
    safStreamTrashMetadataWithIds(context, rootUri, documentAccess).toList()

internal fun safStreamMetadataWithIds(
    context: Context,
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
): Flow<FileMetadataWithId> {
    documentAccess.root() ?: return kotlinx.coroutines.flow.emptyFlow()
    val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
    return safStreamChildDocumentsWithIdsRecursive(context, rootUri, rootDocId)
}

internal fun safStreamTrashMetadataWithIds(
    context: Context,
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
): Flow<FileMetadataWithId> {
    val trashDir = documentAccess.trashDir() ?: return kotlinx.coroutines.flow.emptyFlow()
    val trashDocId = DocumentsContract.getDocumentId(trashDir.uri)
    return safStreamChildDocumentsWithIds(context, rootUri, trashDocId)
}

internal suspend fun safGetFileMetadata(
    documentAccess: SafDocumentAccess,
    filename: String,
): FileMetadata? =
    withContext(SAF_IO_DISPATCHER) {
        safResolveRelative(documentAccess.root(), filename)?.let { file ->
            FileMetadata(filename = filename, lastModified = file.lastModified(), size = file.length())
        }
    }

internal suspend fun safGetTrashFileMetadata(
    documentAccess: SafDocumentAccess,
    filename: String,
): FileMetadata? =
    withContext(SAF_IO_DISPATCHER) {
        documentAccess.trashDir()?.findFile(filename)?.let { file ->
            FileMetadata(filename = filename, lastModified = file.lastModified(), size = file.length())
        }
    }
