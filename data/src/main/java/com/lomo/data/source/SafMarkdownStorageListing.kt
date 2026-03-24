package com.lomo.data.source

import android.content.Context
import android.provider.DocumentsContract
import com.lomo.data.util.runNonFatalCatching
import kotlinx.coroutines.withContext
import timber.log.Timber

internal suspend fun safListFiles(
    documentAccess: SafDocumentAccess,
    targetFilename: String?,
): List<FileContent> =
    withContext(SAF_IO_DISPATCHER) {
        val root = documentAccess.root() ?: return@withContext emptyList()
        root.listFiles().mapNotNull { file ->
            val name = file.name
            if (!safMatchesMarkdownTarget(name, targetFilename)) {
                return@mapNotNull null
            }
            val filename = name ?: return@mapNotNull null
            val content = documentAccess.readTextFromUri(file.uri) ?: return@mapNotNull null
            FileContent(filename = filename, content = content, lastModified = file.lastModified())
        }
    }

internal suspend fun safListTrashFiles(
    documentAccess: SafDocumentAccess,
): List<FileContent> =
    withContext(SAF_IO_DISPATCHER) {
        val trashDir = documentAccess.trashDir() ?: return@withContext emptyList()
        trashDir.listFiles().mapNotNull { file ->
            val name = file.name
            if (!safMatchesMarkdownTarget(name, targetFilename = null)) {
                return@mapNotNull null
            }
            val filename = name ?: return@mapNotNull null
            val content = documentAccess.readTextFromUri(file.uri) ?: return@mapNotNull null
            FileContent(filename = filename, content = content, lastModified = file.lastModified())
        }
    }

internal suspend fun safListMetadata(
    context: Context,
    rootUri: android.net.Uri,
    documentAccess: SafDocumentAccess,
): List<FileMetadata> =
    withContext(SAF_IO_DISPATCHER) {
        runCatching { safQueryChildDocuments(context, rootUri) }
            .getOrNull()
            ?.takeIf(List<FileMetadata>::isNotEmpty)
            ?: safListMetadataSlow(documentAccess)
    }

internal fun safListMetadataSlow(documentAccess: SafDocumentAccess): List<FileMetadata> {
    val root = documentAccess.root() ?: return emptyList()
    return root.listFiles().mapNotNull { file ->
        val name = file.name
        if (safMatchesMarkdownTarget(name, targetFilename = null)) {
            FileMetadata(filename = name ?: return@mapNotNull null, lastModified = file.lastModified())
        } else {
            null
        }
    }
}

internal fun safQueryChildDocuments(
    context: Context,
    rootUri: android.net.Uri,
): List<FileMetadata> {
    val childUri =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            rootUri,
            DocumentsContract.getDocumentId(rootUri),
        )
    val result = mutableListOf<FileMetadata>()
    val projection =
        arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    context.contentResolver.query(childUri, projection, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val timeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        while (cursor.moveToNext()) {
            cursor
                .getStringOrNull(nameIndex)
                ?.takeIf { name -> name.endsWith(SAF_MARKDOWN_SUFFIX) }
                ?.let { name ->
                    result.add(
                        FileMetadata(
                            filename = name,
                            lastModified = cursor.getLongOrZero(timeIndex),
                        ),
                    )
                }
        }
    }
    return result
}

internal suspend fun safListTrashMetadata(
    documentAccess: SafDocumentAccess,
): List<FileMetadata> =
    withContext(SAF_IO_DISPATCHER) {
        val trashDir = documentAccess.trashDir() ?: return@withContext emptyList()
        trashDir.listFiles().mapNotNull { file ->
            val name = file.name
            if (safMatchesMarkdownTarget(name, targetFilename = null)) {
                FileMetadata(filename = name ?: return@mapNotNull null, lastModified = file.lastModified())
            } else {
                null
            }
        }
    }

internal suspend fun safListMetadataWithIds(
    context: Context,
    rootUri: android.net.Uri,
    documentAccess: SafDocumentAccess,
): List<FileMetadataWithId> =
    withContext(SAF_IO_DISPATCHER) {
        runNonFatalCatching {
            val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
            val results = safQueryChildDocumentsWithIds(context, rootUri, rootDocId)
            if (results.isNotEmpty()) {
                results
            } else {
                safListMetadata(context, rootUri, documentAccess).map(::safFallbackMetadataWithId)
            }
        }.getOrElse { error ->
            Timber.e(error, "listMetadataWithIds failed, falling back")
            safListMetadata(context, rootUri, documentAccess).map(::safFallbackMetadataWithId)
        }
    }

internal suspend fun safListTrashMetadataWithIds(
    context: Context,
    rootUri: android.net.Uri,
    documentAccess: SafDocumentAccess,
): List<FileMetadataWithId> =
    withContext(SAF_IO_DISPATCHER) {
        runNonFatalCatching {
            val trashDir = documentAccess.trashDir() ?: return@withContext emptyList()
            val trashDocId = DocumentsContract.getDocumentId(trashDir.uri)
            val results = safQueryChildDocumentsWithIds(context, rootUri, trashDocId)
            if (results.isNotEmpty()) {
                results
            } else {
                safListTrashMetadata(documentAccess).map(::safFallbackMetadataWithId)
            }
        }.getOrElse { error ->
            Timber.e(error, "listTrashMetadataWithIds failed, falling back")
            safListTrashMetadata(documentAccess).map(::safFallbackMetadataWithId)
        }
    }

internal suspend fun safGetFileMetadata(
    documentAccess: SafDocumentAccess,
    filename: String,
): FileMetadata? =
    withContext(SAF_IO_DISPATCHER) {
        documentAccess.root()?.findFile(filename)?.let { file ->
            FileMetadata(filename = filename, lastModified = file.lastModified())
        }
    }

internal suspend fun safGetTrashFileMetadata(
    documentAccess: SafDocumentAccess,
    filename: String,
): FileMetadata? =
    withContext(SAF_IO_DISPATCHER) {
        documentAccess.trashDir()?.findFile(filename)?.let { file ->
            FileMetadata(filename = filename, lastModified = file.lastModified())
        }
    }

private fun safFallbackMetadataWithId(metadata: FileMetadata): FileMetadataWithId =
    FileMetadataWithId(
        filename = metadata.filename,
        lastModified = metadata.lastModified,
        documentId = metadata.filename,
    )
