package com.lomo.data.source

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.ArrayDeque

/**
 * BFS version of [safQueryChildDocumentsWithIds] that recurses into every subdirectory of
 * [baseDocId], skipping the trash subdirectory when it is a direct child. Produces entries with a
 * `filename` relative to the base directory and a fully-resolved document URI.
 */
internal fun safQueryChildDocumentsWithIdsRecursive(
    context: Context,
    rootUri: Uri,
    baseDocId: String,
): List<FileMetadataWithId> =
    safQueryChildDocumentsWithIdsRecursiveCommon(
        context = context,
        rootUri = rootUri,
        baseDocId = baseDocId,
        excludeTrash = true,
        shouldSkip = { _, _ -> false },
        fileFilter = { it.endsWith(SAF_MARKDOWN_SUFFIX) }
    )

internal fun safStreamChildDocumentsWithIdsRecursive(
    documentAccess: SafDocumentAccess,
    rootUri: Uri,
    baseDocId: String,
): Flow<FileMetadataWithId> =
    safStreamChildDocumentsWithIdsRecursiveCommon(
        documentAccess = documentAccess,
        rootUri = rootUri,
        baseDocId = baseDocId,
        excludeTrash = true,
        shouldSkip = { _, _ -> false },
        fileFilter = { it.endsWith(SAF_MARKDOWN_SUFFIX) }
    )

internal fun safQueryChildDocumentsWithIdsRecursiveCommon(
    context: Context,
    rootUri: Uri,
    baseDocId: String,
    excludeTrash: Boolean,
    shouldSkip: (name: String, isDirectory: Boolean) -> Boolean,
    fileFilter: (name: String) -> Boolean,
): List<FileMetadataWithId> {
    val results = mutableListOf<FileMetadataWithId>()
    val queue = ArrayDeque<Pair<String, String>>().apply { add(baseDocId to "") }
    while (queue.isNotEmpty()) {
        val (parentDocId, prefix) = queue.removeFirst()
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
        context.contentResolver.query(childUri, METADATA_WITH_ID_PROJECTION, null, null, null)?.use { cursor ->
            processChildDocumentsCursor(
                cursor = cursor,
                context =
                    ChildDocumentsCursorContext(
                        rootUri = rootUri,
                        prefix = prefix,
                        excludeTrash = excludeTrash,
                        shouldSkip = shouldSkip,
                        fileFilter = fileFilter,
                        queue = queue,
                    ),
                results = results
            )
        }
    }
    return results
}

internal fun safStreamChildDocumentsWithIdsRecursiveCommon(
    documentAccess: SafDocumentAccess,
    rootUri: Uri,
    baseDocId: String,
    excludeTrash: Boolean,
    shouldSkip: (name: String, isDirectory: Boolean) -> Boolean,
    fileFilter: (name: String) -> Boolean,
): Flow<FileMetadataWithId> =
    flow {
        val queue = ArrayDeque<Pair<String, String>>().apply { add(baseDocId to "") }
        while (queue.isNotEmpty()) {
            val (parentDocId, prefix) = queue.removeFirst()
            val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
            val children =
                documentAccess.contentResolver.query(
                    childUri,
                    METADATA_WITH_ID_PROJECTION,
                    null,
                    null,
                    null,
                )
            children?.use { cursor ->
                processStreamChildDocumentsCursor(
                    cursor = cursor,
                    context =
                        ChildDocumentsCursorContext(
                            rootUri = rootUri,
                            prefix = prefix,
                            excludeTrash = excludeTrash,
                            shouldSkip = shouldSkip,
                            fileFilter = fileFilter,
                            queue = queue,
                        )
                )
            }
        }
    }.flowOn(documentAccess.ioDispatcher)

private data class ChildDocumentsCursorContext(
    val rootUri: Uri,
    val prefix: String,
    val excludeTrash: Boolean,
    val shouldSkip: (name: String, isDirectory: Boolean) -> Boolean,
    val fileFilter: (name: String) -> Boolean,
    val queue: ArrayDeque<Pair<String, String>>,
)

private fun processChildDocumentsCursor(
    cursor: Cursor,
    context: ChildDocumentsCursorContext,
    results: MutableList<FileMetadataWithId>
) {
    val idx = MetadataColumnIndexes.from(cursor)
    while (cursor.moveToNext()) {
        val row = MetadataRow.read(cursor, idx)
        if (row != null && !context.shouldSkip(row.name, row.isDirectory)) {
            val relativePath = if (context.prefix.isEmpty()) row.name else "${context.prefix}/${row.name}"
            if (row.isDirectory) {
                val isTrash = context.excludeTrash && context.prefix.isEmpty() && row.name == SAF_TRASH_DIR_NAME
                if (!isTrash) {
                    context.queue.add(row.docId to relativePath)
                }
            } else if (context.fileFilter(row.name)) {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(context.rootUri, row.docId)
                results.add(
                    FileMetadataWithId(
                        filename = relativePath,
                        lastModified = row.lastModified,
                        documentId = row.docId,
                        uriString = docUri.toString(),
                        size = row.size,
                    )
                )
            }
        }
    }
}

private suspend fun kotlinx.coroutines.flow.FlowCollector<FileMetadataWithId>.processStreamChildDocumentsCursor(
    cursor: Cursor,
    context: ChildDocumentsCursorContext
) {
    val idx = MetadataColumnIndexes.from(cursor)
    while (cursor.moveToNext()) {
        val row = MetadataRow.read(cursor, idx)
        if (row != null && !context.shouldSkip(row.name, row.isDirectory)) {
            val relativePath = if (context.prefix.isEmpty()) row.name else "${context.prefix}/${row.name}"
            if (row.isDirectory) {
                val isTrash = context.excludeTrash && context.prefix.isEmpty() && row.name == SAF_TRASH_DIR_NAME
                if (!isTrash) {
                    context.queue.add(row.docId to relativePath)
                }
            } else if (context.fileFilter(row.name)) {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(context.rootUri, row.docId)
                emit(
                    FileMetadataWithId(
                        filename = relativePath,
                        lastModified = row.lastModified,
                        documentId = row.docId,
                        uriString = docUri.toString(),
                        size = row.size,
                    )
                )
            }
        }
    }
}

private val METADATA_WITH_ID_PROJECTION =
    arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )
