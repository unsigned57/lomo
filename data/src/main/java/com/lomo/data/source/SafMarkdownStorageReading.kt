package com.lomo.data.source

import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.withContext

internal suspend fun safReadFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): String? =
    withContext(SAF_IO_DISPATCHER) {
        val file = documentAccess.root()?.findFile(filename) ?: return@withContext null
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
