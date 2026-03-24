package com.lomo.data.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.util.runNonFatalCatching
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

internal suspend fun safSaveFile(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
    filename: String,
    content: String,
    append: Boolean,
    uri: Uri?,
): String? =
    withContext(SAF_IO_DISPATCHER) {
        documentAccess.withSecurityRetryOrThrow(operation = "saveFile($filename)") {
            safWriteUsingKnownUri(documentAccess, uri, content, append)
                ?: safWriteUsingResolvedFile(rootUri, documentAccess, filename, content, append)
        }
    }

internal suspend fun safSaveTrashFile(
    documentAccess: SafDocumentAccess,
    filename: String,
    content: String,
    append: Boolean,
) = withContext(SAF_IO_DISPATCHER) {
    documentAccess.withSecurityRetryOrThrow(operation = "saveTrashFile($filename)") {
        val trash =
            documentAccess.orCreateTrashDir()
                ?: throw SecurityException("Cannot access SAF trash for saveTrashFile")
        val targetUri = safResolveOrCreateFileUri(trash, filename)
        safWriteMarkdownContent(documentAccess, targetUri, content, append)
    }
}

internal suspend fun safDeleteFile(
    context: Context,
    documentAccess: SafDocumentAccess,
    filename: String,
    uri: Uri?,
) = withContext(SAF_IO_DISPATCHER) {
    documentAccess.withSecurityRetry(
        operation = "deleteFile($filename)",
        fallbackValue = Unit,
    ) {
        if (
            uri != null &&
            runCatching {
                android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
            }.isSuccess
        ) {
            return@withSecurityRetry Unit
        }
        if (uri != null) {
            Timber.w("Failed to delete using cached URI, falling back to findFile")
        }
        val root = documentAccess.root() ?: throw SecurityException("Cannot access SAF root for deleteFile")
        root.findFile(filename)?.delete()
        Unit
    }
}

internal suspend fun safDeleteTrashFile(
    documentAccess: SafDocumentAccess,
    filename: String,
) = withContext(SAF_IO_DISPATCHER) {
    documentAccess.trashDir()?.findFile(filename)?.delete()
    Unit
}

private fun safWriteUsingKnownUri(
    documentAccess: SafDocumentAccess,
    uri: Uri?,
    content: String,
    append: Boolean,
): String? {
    if (uri == null) {
        return null
    }
    return runNonFatalCatching {
        safWriteMarkdownContent(documentAccess, uri, content, append)
        uri.toString()
    }.getOrElse { error ->
        Timber.w(error, "Failed to write using cached URI, falling back to findFile")
        null
    }
}

private fun safWriteUsingResolvedFile(
    rootUri: Uri,
    documentAccess: SafDocumentAccess,
    filename: String,
    content: String,
    append: Boolean,
): String {
    val root = documentAccess.root() ?: throw SecurityException("Cannot access SAF root for saveFile")
    val targetUri = safResolveOrCreateFileUri(root, filename)
    safWriteMarkdownContent(documentAccess, targetUri, content, append)
    return if (targetUri.toString().isNotBlank()) {
        targetUri.toString()
    } else {
        rootUri.toString()
    }
}

private fun safResolveOrCreateFileUri(
    parent: DocumentFile,
    filename: String,
): Uri =
    parent.findFile(filename)?.uri
        ?: parent.createFile("text/markdown", filename)?.uri
        ?: throw IOException("Failed to resolve SAF file for $filename")

private fun safWriteMarkdownContent(
    documentAccess: SafDocumentAccess,
    uri: Uri,
    content: String,
    append: Boolean,
) {
    if (append) {
        documentAccess.writeTextToUri(uri, "wa", content)
    } else {
        documentAccess.overwriteWithRollback(uri, content)
    }
}
