package com.lomo.data.source

import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.data.util.md5Hex
import com.lomo.data.util.runNonFatalCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

internal suspend fun safReadFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): String? =
    withContext(documentAccess.ioDispatcher) {
        val file = safResolveRelativeDocument(documentAccess, filename) ?: return@withContext null
        documentAccess.readTextFromUri(file.uri)
    }

internal suspend fun safReadFileUri(
    documentAccess: SafDocumentAccess,
    uri: Uri,
): String? =
    withContext(documentAccess.ioDispatcher) {
        documentAccess.readTextFromUri(uri)
    }

internal suspend fun safReadTrashFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): String? =
    withContext(documentAccess.ioDispatcher) {
        val file = safResolveTrashDocument(documentAccess, filename) ?: return@withContext null
        documentAccess.readTextFromUri(file.uri)
    }

internal suspend fun safFingerprintFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): String? =
    withContext(documentAccess.ioDispatcher) {
        val file = safResolveRelativeDocument(documentAccess, filename) ?: return@withContext null
        documentAccess.contentResolver.openInputStream(file.uri)?.use { input -> input.md5Hex() }
    }

internal suspend fun safFingerprintTrashFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): String? =
    withContext(documentAccess.ioDispatcher) {
        val file = safResolveTrashDocument(documentAccess, filename) ?: return@withContext null
        documentAccess.contentResolver.openInputStream(file.uri)?.use { input -> input.md5Hex() }
    }

internal suspend fun safReadFileByDocumentId(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
    documentId: String,
): String? =
    withContext(documentAccess.ioDispatcher) {
        // behavior-contract: silent-result-ok: a cached docId may go stale against the provider;
        // fall back to the by-name resolver before reporting the file unreadable
        try {
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
            documentAccess.readTextFromUri(fileUri)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            safReadFile(documentAccess, documentId)
        }
    }

internal fun safStreamFileByDocumentId(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
    documentId: String,
): Flow<String> =
    // behavior-contract: silent-result-ok: a cached docId may go stale against the provider;
    // fall back to the by-name resolver before reporting the stream unavailable
    runNonFatalCatching {
        val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
        documentAccess.streamTextLinesFromUri(fileUri)
    }.getOrElse {
        safStreamFile(documentAccess, documentId)
    }

internal fun safStreamFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): Flow<String> {
    val file = safResolveRelativeDocument(documentAccess, filename) ?: return emptyFlow()
    return documentAccess.streamTextLinesFromUri(file.uri)
}

internal suspend fun safReadTrashFileByDocumentId(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
    documentId: String,
): String? =
    withContext(documentAccess.ioDispatcher) {
        // behavior-contract: silent-result-ok: a cached docId may go stale against the provider;
        // fall back to the by-name trash resolver before reporting the record unreadable
        try {
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
            documentAccess.readTextFromUri(fileUri)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            safReadTrashFile(documentAccess, documentId)
        }
    }

internal fun safStreamTrashFileByDocumentId(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
    documentId: String,
): Flow<String> =
    // behavior-contract: silent-result-ok: a cached docId may go stale against the provider;
    // fall back to the by-name trash resolver before reporting the stream unavailable
    runNonFatalCatching {
        val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
        documentAccess.streamTextLinesFromUri(fileUri)
    }.getOrElse {
        safStreamTrashFile(documentAccess, documentId)
    }

internal fun safStreamTrashFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): Flow<String> {
    val file = safResolveTrashDocument(documentAccess, filename) ?: return emptyFlow()
    return documentAccess.streamTextLinesFromUri(file.uri)
}
