package com.lomo.data.source

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.ArrayDeque

/**
 * Walks the main markdown root (excluding the trash subdirectory) and emits a flat list of
 * `(DocumentFile, relativePath)` pairs for every `.md` file reachable from the tree.
 *
 * Relative paths always use `/` as the separator so they can be used uniformly with the direct
 * file backend and later passed to [safResolveRelative] for lookup.
 */
internal fun safWalkMainMarkdownFiles(
    documentAccess: SafDocumentAccess,
): List<Pair<DocumentFile, String>> {
    val root = documentAccess.root() ?: return emptyList()
    val queue = ArrayDeque<Pair<DocumentFile, String>>().apply { add(root to "") }
    val results = mutableListOf<Pair<DocumentFile, String>>()
    while (queue.isNotEmpty()) {
        val (dir, prefix) = queue.removeFirst()
        dir.listFiles().forEach { child ->
            visitDocumentChild(child, prefix, results, queue)
        }
    }
    return results
}

private fun visitDocumentChild(
    child: DocumentFile,
    prefix: String,
    results: MutableList<Pair<DocumentFile, String>>,
    queue: ArrayDeque<Pair<DocumentFile, String>>,
) {
    val name = child.name ?: return
    val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
    when {
        child.isDirectory && prefix.isEmpty() && name == SAF_TRASH_DIR_NAME -> Unit
        child.isDirectory -> queue.add(child to relativePath)
        name.endsWith(SAF_MARKDOWN_SUFFIX) -> results.add(child to relativePath)
    }
}

/**
 * BFS cursor-backed walk of the main root that returns metadata for every `.md` file, skipping a
 * top-level `.trash` directory. Uses DocumentsContract child-document queries so it is faster than
 * walking DocumentFile trees for large workspaces.
 */
internal fun safQueryMainMarkdownMetadataRecursive(
    context: Context,
    rootUri: Uri,
): List<FileMetadata> {
    val results = mutableListOf<FileMetadata>()
    val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
    val queue = ArrayDeque<Pair<String, String>>().apply { add(rootDocId to "") }
    while (queue.isNotEmpty()) {
        val (parentDocId, prefix) = queue.removeFirst()
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
        context.contentResolver.query(childUri, METADATA_PROJECTION, null, null, null)?.use { cursor ->
            drainMetadataCursor(cursor, prefix, queue, results)
        }
    }
    return results
}

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
    context: Context,
    rootUri: Uri,
    baseDocId: String,
): Flow<FileMetadataWithId> =
    safStreamChildDocumentsWithIdsRecursiveCommon(
        context = context,
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
                rootUri = rootUri,
                prefix = prefix,
                excludeTrash = excludeTrash,
                shouldSkip = shouldSkip,
                fileFilter = fileFilter,
                queue = queue,
                results = results
            )
        }
    }
    return results
}

internal fun safStreamChildDocumentsWithIdsRecursiveCommon(
    context: Context,
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
            context.contentResolver.query(childUri, METADATA_WITH_ID_PROJECTION, null, null, null)?.use { cursor ->
                processStreamChildDocumentsCursor(
                    cursor = cursor,
                    rootUri = rootUri,
                    prefix = prefix,
                    excludeTrash = excludeTrash,
                    shouldSkip = shouldSkip,
                    fileFilter = fileFilter,
                    queue = queue
                )
            }
        }
    }.flowOn(SAF_IO_DISPATCHER)

private fun processChildDocumentsCursor(
    cursor: Cursor,
    rootUri: Uri,
    prefix: String,
    excludeTrash: Boolean,
    shouldSkip: (name: String, isDirectory: Boolean) -> Boolean,
    fileFilter: (name: String) -> Boolean,
    queue: ArrayDeque<Pair<String, String>>,
    results: MutableList<FileMetadataWithId>
) {
    val idx = MetadataColumnIndexes.from(cursor)
    while (cursor.moveToNext()) {
        val row = MetadataRow.read(cursor, idx)
        if (row != null && !shouldSkip(row.name, row.isDirectory)) {
            val relativePath = if (prefix.isEmpty()) row.name else "$prefix/${row.name}"
            if (row.isDirectory) {
                val isTrash = excludeTrash && prefix.isEmpty() && row.name == SAF_TRASH_DIR_NAME
                if (!isTrash) {
                    queue.add(row.docId to relativePath)
                }
            } else if (fileFilter(row.name)) {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, row.docId)
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
    rootUri: Uri,
    prefix: String,
    excludeTrash: Boolean,
    shouldSkip: (name: String, isDirectory: Boolean) -> Boolean,
    fileFilter: (name: String) -> Boolean,
    queue: ArrayDeque<Pair<String, String>>
) {
    val idx = MetadataColumnIndexes.from(cursor)
    while (cursor.moveToNext()) {
        val row = MetadataRow.read(cursor, idx)
        if (row != null && !shouldSkip(row.name, row.isDirectory)) {
            val relativePath = if (prefix.isEmpty()) row.name else "$prefix/${row.name}"
            if (row.isDirectory) {
                val isTrash = excludeTrash && prefix.isEmpty() && row.name == SAF_TRASH_DIR_NAME
                if (!isTrash) {
                    queue.add(row.docId to relativePath)
                }
            } else if (fileFilter(row.name)) {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, row.docId)
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

private fun drainMetadataCursor(
    cursor: Cursor,
    prefix: String,
    queue: ArrayDeque<Pair<String, String>>,
    results: MutableList<FileMetadata>,
) {
    val idx = MetadataColumnIndexes.from(cursor)
    while (cursor.moveToNext()) {
        val row = MetadataRow.read(cursor, idx)
        if (row != null) {
            processMetadataRow(row, prefix, queue)?.let(results::add)
        }
    }
}

private fun processMetadataRow(
    row: MetadataRow,
    prefix: String,
    queue: ArrayDeque<Pair<String, String>>,
): FileMetadata? {
    val relativePath = if (prefix.isEmpty()) row.name else "$prefix/${row.name}"
    return when {
        row.isDirectory && prefix.isEmpty() && row.name == SAF_TRASH_DIR_NAME -> null
        row.isDirectory -> {
            queue.add(row.docId to relativePath)
            null
        }
        row.name.endsWith(SAF_MARKDOWN_SUFFIX) ->
            FileMetadata(filename = relativePath, lastModified = row.lastModified, size = row.size)
        else -> null
    }
}

private val METADATA_PROJECTION =
    arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )

private val METADATA_WITH_ID_PROJECTION =
    arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )

private data class MetadataColumnIndexes(
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

private data class MetadataRow(
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
