package com.lomo.data.source

import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source

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

private fun SafDocumentAccess.streamTextLinesFromUri(uri: Uri): Flow<String> =
    flow {
        val input = contentResolver.openInputStream(uri) ?: return@flow
        input.source().buffer().use { source ->
            while (true) {
                val line = source.readUtf8Line() ?: break
                emit(line)
            }
        }
    }
