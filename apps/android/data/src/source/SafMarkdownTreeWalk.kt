package com.lomo.data.source

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.util.ArrayDeque

/**
 * Walks the main markdown root (excluding the trash subdirectory) and emits a flat list of
 * `(DocumentFile, relativePath)` pairs for every `.md` file reachable from the tree.
 *
 * Relative paths always use `/` as the separator so they can be used uniformly with the direct
 * file backend and later passed to [safResolveRelativeDocument] for lookup.
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
