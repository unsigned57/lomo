package com.lomo.data.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.data.util.runNonFatalCatching
import kotlinx.coroutines.withContext
import timber.log.Timber

internal suspend fun safListMetadata(
    context: Context,
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
): List<FileMetadata> =
    withContext(SAF_IO_DISPATCHER) {
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
    withContext(SAF_IO_DISPATCHER) {
        runNonFatalCatching {
            val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
            val results = safQueryChildDocumentsWithIdsRecursive(context, rootUri, rootDocId)
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
    rootUri: Uri,
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

private fun safFallbackMetadataWithId(metadata: FileMetadata): FileMetadataWithId =
    FileMetadataWithId(
        filename = metadata.filename,
        lastModified = metadata.lastModified,
        documentId = metadata.filename,
    )
