package com.lomo.data.source

import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.data.util.md5Hex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

internal suspend fun safReadFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): String? =
    withContext(SAF_IO_DISPATCHER) {
        val file = safResolveRelative(documentAccess.root(), filename) ?: return@withContext null
        documentAccess.readTextFromUri(file.uri)
    }

internal suspend fun safReadFileUri(
    documentAccess: SafDocumentAccess,
    uri: Uri,
): String? =
    withContext(SAF_IO_DISPATCHER) {
        documentAccess.readTextFromUri(uri)
    }

internal suspend fun safReadTrashFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): String? =
    withContext(SAF_IO_DISPATCHER) {
        val file = documentAccess.trashDir()?.findFile(filename) ?: return@withContext null
        documentAccess.readTextFromUri(file.uri)
    }

internal suspend fun safFingerprintFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): String? =
    withContext(SAF_IO_DISPATCHER) {
        val file = safResolveRelative(documentAccess.root(), filename) ?: return@withContext null
        documentAccess.contentResolver.openInputStream(file.uri)?.use { input -> input.md5Hex() }
    }

internal suspend fun safFingerprintTrashFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): String? =
    withContext(SAF_IO_DISPATCHER) {
        val file = documentAccess.trashDir()?.findFile(filename) ?: return@withContext null
        documentAccess.contentResolver.openInputStream(file.uri)?.use { input -> input.md5Hex() }
    }

internal suspend fun safReadFileByDocumentId(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
    documentId: String,
): String? =
    withContext(SAF_IO_DISPATCHER) {
        runCatching {
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
            documentAccess.readTextFromUri(fileUri)
        }.getOrElse {
            safReadFile(documentAccess, documentId)
        }
    }

internal fun safStreamFileByDocumentId(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
    documentId: String,
): Flow<String> =
    runCatching {
        val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
        documentAccess.streamTextLinesFromUri(fileUri)
    }.getOrElse {
        safStreamFile(documentAccess, documentId)
    }

internal fun safStreamFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): Flow<String> {
    val file = safResolveRelative(documentAccess.root(), filename) ?: return emptyFlow()
    return documentAccess.streamTextLinesFromUri(file.uri)
}

internal suspend fun safReadTrashFileByDocumentId(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
    documentId: String,
): String? =
    withContext(SAF_IO_DISPATCHER) {
        runCatching {
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
            documentAccess.readTextFromUri(fileUri)
        }.getOrElse {
            safReadTrashFile(documentAccess, documentId)
        }
    }

internal fun safStreamTrashFileByDocumentId(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
    documentId: String,
): Flow<String> =
    runCatching {
        val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
        documentAccess.streamTextLinesFromUri(fileUri)
    }.getOrElse {
        safStreamTrashFile(documentAccess, documentId)
    }

internal fun safStreamTrashFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): Flow<String> {
    val file = documentAccess.trashDir()?.findFile(filename) ?: return emptyFlow()
    return documentAccess.streamTextLinesFromUri(file.uri)
}
