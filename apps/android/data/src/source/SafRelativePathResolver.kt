package com.lomo.data.source

import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract

/**
 * One child document resolved through a provider children query. The cursor row supplies the
 * document id and all metadata fields — a resolved child is never re-queried per column through
 * `DocumentFile` accessors, and its document URI is derived from the row's own docId.
 */
internal data class SafResolvedDocument(
    val documentId: String,
    val uri: Uri,
    val name: String?,
    val isDirectory: Boolean,
    val lastModified: Long?,
    val size: Long?,
)

/**
 * Lists the direct children of [parentDocId] in one provider query.
 *
 * One `query` round-trip materializes every child's id/name/mime/mtime/size; the previous
 * `DocumentFile.listFiles()` + per-child `name`/`lastModified()`/`length()` walk cost one query
 * per column per child instead.
 */
internal fun safQueryChildDocuments(
    documentAccess: SafDocumentAccess,
    parentDocId: String,
): List<SafResolvedDocument> {
    val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(documentAccess.treeUri, parentDocId)
    val results = mutableListOf<SafResolvedDocument>()
    documentAccess.contentResolver
        .query(childUri, METADATA_PROJECTION, null, null, null)
        ?.use { cursor ->
            val idx = MetadataColumnIndexes.from(cursor)
            while (cursor.moveToNext()) {
                MetadataRow.read(cursor, idx)?.let { row ->
                    results.add(row.toResolvedDocument(documentAccess.treeUri))
                }
            }
        }
    return results
}

/**
 * Resolves the direct child named [name] of [parentDocId] in one provider query.
 * Returns `null` when the provider cannot enumerate the parent or no child carries that name.
 */
internal fun safFindChildDocument(
    documentAccess: SafDocumentAccess,
    parentDocId: String,
    name: String,
): SafResolvedDocument? {
    val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(documentAccess.treeUri, parentDocId)
    documentAccess.contentResolver
        .query(childUri, METADATA_PROJECTION, null, null, null)
        ?.use { cursor ->
            val idx = MetadataColumnIndexes.from(cursor)
            while (cursor.moveToNext()) {
                val row = MetadataRow.read(cursor, idx) ?: continue
                if (row.name == name) {
                    return row.toResolvedDocument(documentAccess.treeUri)
                }
            }
        }
    return null
}

/**
 * Walks a `/`-separated relative path from the bound root through provider children queries,
 * one query per segment. The leaf row's metadata answers stat callers directly — no second
 * `lastModified`/`length` round-trip and no `DocumentsContract.getDocumentId` re-parse per hop.
 *
 * A blank path resolves to the root itself, matching the legacy `DocumentFile` walk contract.
 */
internal fun safResolveRelativeDocument(
    documentAccess: SafDocumentAccess,
    relativePath: String,
): SafResolvedDocument? {
    val root = documentAccess.root() ?: return null
    val rootDocId = DocumentsContract.getDocumentId(root.uri)
    if (relativePath.isBlank()) {
        return SafResolvedDocument(
            documentId = rootDocId,
            uri = root.uri,
            name = null,
            isDirectory = true,
            lastModified = null,
            size = null,
        )
    }
    var parentDocId = rootDocId
    var current: SafResolvedDocument? = null
    for (segment in relativePath.split('/')) {
        if (segment.isEmpty()) continue
        current = safFindChildDocument(documentAccess, parentDocId, segment) ?: return null
        parentDocId = current.documentId
    }
    return current
}

/**
 * Resolves one markdown document inside the trash directory without enumerating it twice.
 * The trash directory itself stays behind [SafDocumentAccess.trashDir]'s cached grant check.
 */
internal fun safResolveTrashDocument(
    documentAccess: SafDocumentAccess,
    filename: String,
): SafResolvedDocument? {
    val trashDocId =
        documentAccess.trashDir()?.let { DocumentsContract.getDocumentId(it.uri) }
            ?: return null
    return safFindChildDocument(documentAccess, trashDocId, filename)
}

/**
 * Lists every markdown child of the trash directory in one provider query.
 */
internal fun safQueryTrashChildren(
    documentAccess: SafDocumentAccess,
): List<SafResolvedDocument> {
    val trashDocId =
        documentAccess.trashDir()?.let { DocumentsContract.getDocumentId(it.uri) }
            ?: return emptyList()
    return safQueryChildDocuments(documentAccess, trashDocId)
}

private fun MetadataRow.toResolvedDocument(rootUri: Uri): SafResolvedDocument =
    SafResolvedDocument(
        documentId = docId,
        uri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId),
        name = name,
        isDirectory = isDirectory,
        lastModified = lastModified,
        size = size,
    )

internal val METADATA_PROJECTION =
    arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )

internal data class MetadataColumnIndexes(
    val docId: Int,
    val name: Int,
    val mime: Int,
    val time: Int,
    val size: Int,
) {
    companion object {
        fun from(cursor: Cursor): MetadataColumnIndexes =
            MetadataColumnIndexes(
                docId = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                name = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                mime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE),
                time = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                size = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE),
            )
    }
}

internal data class MetadataRow(
    val docId: String,
    val name: String,
    val isDirectory: Boolean,
    val lastModified: Long,
    val size: Long?,
) {
    companion object {
        fun read(cursor: Cursor, indexes: MetadataColumnIndexes): MetadataRow? {
            val docId = cursor.getStringOrNull(indexes.docId) ?: return null
            val name = cursor.getStringOrNull(indexes.name) ?: return null
            val mime = cursor.getStringOrNull(indexes.mime)
            return MetadataRow(
                docId = docId,
                name = name,
                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                lastModified = cursor.getLongOrZero(indexes.time),
                size = cursor.getLongOrNull(indexes.size),
            )
        }
    }
}
