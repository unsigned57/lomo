package com.lomo.data.source

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract

internal fun safQueryChildDocumentsWithIds(
    context: Context,
    rootUri: Uri,
    parentDocId: String,
): List<FileMetadataWithId> {
    val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
    val result = mutableListOf<FileMetadataWithId>()
    val projection =
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    context.contentResolver.query(childUri, projection, null, null, null)?.use { cursor ->
        val indexes =
            SafCursorColumnIndexes(
                documentId = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                displayName = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                lastModified = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            )
        while (cursor.moveToNext()) {
            safMapCursorToMetadataWithId(cursor, indexes, rootUri)?.let(result::add)
        }
    }
    return result
}

private fun safMapCursorToMetadataWithId(
    cursor: Cursor,
    indexes: SafCursorColumnIndexes,
    rootUri: Uri,
): FileMetadataWithId? {
    val docId = cursor.getStringOrNull(indexes.documentId)
    val name = cursor.getStringOrNull(indexes.displayName)
    return if (docId != null && name != null && name.endsWith(SAF_MARKDOWN_SUFFIX)) {
        val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
        FileMetadataWithId(
            filename = name,
            lastModified = cursor.getLongOrZero(indexes.lastModified),
            documentId = docId,
            uriString = fileUri.toString(),
        )
    } else {
        null
    }
}

internal fun Cursor.getStringOrNull(index: Int): String? = if (index != -1) getString(index) else null

internal fun Cursor.getLongOrZero(index: Int): Long = if (index != -1) getLong(index) else 0L

private data class SafCursorColumnIndexes(
    val documentId: Int,
    val displayName: Int,
    val lastModified: Int,
)
