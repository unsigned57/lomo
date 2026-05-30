package com.lomo.data.source

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.util.ArrayDeque

/**
 * Walks a relative path (segments separated by `/`) from [root], returning the leaf DocumentFile
 * when every intermediate directory exists. Returns `null` if any segment is missing. The caller
 * is responsible for excluding the trash subdirectory if that matters to the use case.
 */
internal fun safResolveRelative(
    root: DocumentFile?,
    relativePath: String,
): DocumentFile? {
    if (root == null) return null
    if (relativePath.isBlank()) return root
    var current = root
    for (segment in relativePath.split('/')) {
        if (segment.isEmpty()) continue
        current = current?.findFile(segment) ?: return null
    }
    return current
}

/**
 * Walks a relative path (segments separated by `/`), creating any missing directories on the way
 * and creating the leaf file with [leafMimeType] if it does not exist. Returns the existing or
 * freshly-created leaf DocumentFile, or throws if creation fails at any step.
 */
internal fun safResolveOrCreateRelative(
    root: DocumentFile,
    relativePath: String,
    leafMimeType: String,
): DocumentFile {
    val segments = relativePath.split('/').filter(String::isNotEmpty)
    require(segments.isNotEmpty()) { "relativePath must not be empty" }
    var current = root
    for (index in 0 until segments.size - 1) {
        val segment = segments[index]
        current = current.findFile(segment)
            ?: current.createDirectory(segment)
            ?: throw IOException("Failed to create SAF directory segment '$segment' for $relativePath")
    }
    val leafName = segments.last()
    return current.findFile(leafName)
        ?: current.createFile(leafMimeType, leafName)
        ?: throw IOException("Failed to create SAF leaf '$leafName' for $relativePath")
}

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
): List<FileMetadataWithId> {
    val results = mutableListOf<FileMetadataWithId>()
    val queue = ArrayDeque<Pair<String, String>>().apply { add(baseDocId to "") }
    while (queue.isNotEmpty()) {
        val (parentDocId, prefix) = queue.removeFirst()
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
        context.contentResolver.query(childUri, METADATA_WITH_ID_PROJECTION, null, null, null)?.use { cursor ->
            drainMetadataWithIdCursor(cursor, rootUri, prefix, queue, results)
        }
    }
    return results
}

internal fun safStreamChildDocumentsWithIdsRecursive(
    context: Context,
    rootUri: Uri,
    baseDocId: String,
): Flow<FileMetadataWithId> =
    flow {
        val queue = ArrayDeque<Pair<String, String>>().apply { add(baseDocId to "") }
        while (queue.isNotEmpty()) {
            val (parentDocId, prefix) = queue.removeFirst()
            val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
            context.contentResolver.query(childUri, METADATA_WITH_ID_PROJECTION, null, null, null)?.use { cursor ->
                val idx = MetadataColumnIndexes.from(cursor)
                while (cursor.moveToNext()) {
                    val row = MetadataRow.read(cursor, idx) ?: continue
                    processMetadataWithIdRow(row, rootUri, prefix, queue)?.let { metadata ->
                        emit(metadata)
                    }
                }
            }
        }
    }.flowOn(SAF_IO_DISPATCHER)

private fun drainMetadataCursor(
    cursor: Cursor,
    prefix: String,
    queue: ArrayDeque<Pair<String, String>>,
    results: MutableList<FileMetadata>,
) {
    val idx = MetadataColumnIndexes.from(cursor)
    while (cursor.moveToNext()) {
        val row = MetadataRow.read(cursor, idx) ?: continue
        processMetadataRow(row, prefix, queue)?.let(results::add)
    }
}

private fun drainMetadataWithIdCursor(
    cursor: Cursor,
    rootUri: Uri,
    prefix: String,
    queue: ArrayDeque<Pair<String, String>>,
    results: MutableList<FileMetadataWithId>,
) {
    val idx = MetadataColumnIndexes.from(cursor)
    while (cursor.moveToNext()) {
        val row = MetadataRow.read(cursor, idx) ?: continue
        processMetadataWithIdRow(row, rootUri, prefix, queue)?.let(results::add)
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

private fun processMetadataWithIdRow(
    row: MetadataRow,
    rootUri: Uri,
    prefix: String,
    queue: ArrayDeque<Pair<String, String>>,
): FileMetadataWithId? {
    val relativePath = if (prefix.isEmpty()) row.name else "$prefix/${row.name}"
    return when {
        row.isDirectory && prefix.isEmpty() && row.name == SAF_TRASH_DIR_NAME -> null
        row.isDirectory -> {
            queue.add(row.docId to relativePath)
            null
        }
        row.name.endsWith(SAF_MARKDOWN_SUFFIX) ->
            FileMetadataWithId(
                filename = relativePath,
                lastModified = row.lastModified,
                documentId = row.docId,
                uriString = DocumentsContract.buildDocumentUriUsingTree(rootUri, row.docId).toString(),
            )
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
