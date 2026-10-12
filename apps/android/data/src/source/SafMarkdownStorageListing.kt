package com.lomo.data.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.data.util.runNonFatalCatching
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

internal suspend fun safListMetadata(
    context: Context,
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
): List<FileMetadata> =
    withContext(documentAccess.ioDispatcher) {
        // behavior-contract: silent-result-ok: fast recursive query may fail; falls to safListMetadataSlow
        runNonFatalCatching { safQueryMainMarkdownMetadataRecursive(context, rootUri) }
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
    withContext(documentAccess.ioDispatcher) {
        // One provider children query answers the whole trash listing; every row already carries
        // the name/mtime/size a `DocumentFile` child would re-query per column.
        safQueryTrashChildren(documentAccess).mapNotNull { file ->
            val name = file.name
            if (safMatchesMarkdownTarget(name, targetFilename = null)) {
                FileMetadata(
                    filename = name ?: return@mapNotNull null,
                    lastModified = file.lastModified ?: 0L,
                    size = file.size,
                )
            } else {
                null
            }
        }
    }

internal suspend fun safListMetadataWithIds(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
): List<FileMetadataWithId> =
    safStreamMetadataWithIds(rootUri, documentAccess).toList()

internal suspend fun safListTrashMetadataWithIds(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
): List<FileMetadataWithId> =
    safStreamTrashMetadataWithIds(rootUri, documentAccess).toList()

internal fun safStreamMetadataWithIds(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
): Flow<FileMetadataWithId> {
    documentAccess.root() ?: return kotlinx.coroutines.flow.emptyFlow()
    val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
    return safStreamChildDocumentsWithIdsRecursive(documentAccess, rootUri, rootDocId)
}

internal fun safStreamTrashMetadataWithIds(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
): Flow<FileMetadataWithId> {
    val trashDir = documentAccess.trashDir() ?: return kotlinx.coroutines.flow.emptyFlow()
    val trashDocId = DocumentsContract.getDocumentId(trashDir.uri)
    return safStreamChildDocumentsWithIds(documentAccess, rootUri, trashDocId)
}

internal suspend fun safGetFileMetadata(
    documentAccess: SafDocumentAccess,
    filename: String,
): FileMetadata? =
    withContext(documentAccess.ioDispatcher) {
        safResolveRelativeDocument(documentAccess, filename)?.let { file ->
            FileMetadata(filename = filename, lastModified = file.lastModified ?: 0L, size = file.size)
        }
    }

internal suspend fun safGetTrashFileMetadata(
    documentAccess: SafDocumentAccess,
    filename: String,
): FileMetadata? =
    withContext(documentAccess.ioDispatcher) {
        safResolveTrashDocument(documentAccess, filename)?.let { file ->
            FileMetadata(filename = filename, lastModified = file.lastModified ?: 0L, size = file.size)
        }
    }
