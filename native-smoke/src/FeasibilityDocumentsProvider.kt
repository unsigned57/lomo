package com.lomo.nativesmoke

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Deterministic SAF surface for stage-0 recovery smoke only.
 *
 * Production modules must not reference this provider. Root is private app storage under
 * `files/saf-root`; document ids are relative paths within that root.
 */
class FeasibilityDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(columns)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                DocumentsContract.Root.COLUMN_ROOT_ID -> row.add(ROOT_ID)
                DocumentsContract.Root.COLUMN_DOCUMENT_ID -> row.add(ROOT_DOC_ID)
                DocumentsContract.Root.COLUMN_TITLE -> row.add("Lomo Feasibility SAF")
                DocumentsContract.Root.COLUMN_FLAGS ->
                    row.add(
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                            DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
                    )
                DocumentsContract.Root.COLUMN_MIME_TYPES -> row.add("*/*")
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> row.add(null)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor = singleDocumentCursor(documentId, projection)

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(columns)
        val parent = resolve(parentDocumentId)
        if (parent.isDirectory) {
            parent.listFiles()?.sortedBy { it.name }?.forEach { child ->
                appendDocument(cursor, columns, toDocumentId(child))
            }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = resolve(documentId)
        if (!file.exists() && mode.contains("w")) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        if (!file.exists()) {
            throw FileNotFoundException(documentId)
        }
        val parcelMode =
            when {
                mode.contains("w") && mode.contains("r") -> ParcelFileDescriptor.MODE_READ_WRITE
                mode.contains("w") -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE
                else -> ParcelFileDescriptor.MODE_READ_ONLY
            }
        return ParcelFileDescriptor.open(file, parcelMode)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: android.graphics.Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor? = null

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String?,
        displayName: String,
    ): String {
        val parent = resolve(parentDocumentId)
        if (!parent.isDirectory) {
            parent.mkdirs()
        }
        val target = File(parent, displayName)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            target.mkdirs()
        } else {
            target.parentFile?.mkdirs()
            target.writeBytes(ByteArray(0))
        }
        return toDocumentId(target)
    }

    override fun deleteDocument(documentId: String) {
        val file = resolve(documentId)
        if (file.exists() && !file.deleteRecursively()) {
            throw FileNotFoundException("unable to delete $documentId")
        }
    }

    override fun renameDocument(
        documentId: String,
        displayName: String,
    ): String {
        val source = resolve(documentId)
        val target = File(source.parentFile, displayName)
        if (!source.renameTo(target)) {
            throw FileNotFoundException("unable to rename $documentId")
        }
        return toDocumentId(target)
    }

    /**
     * Move a document into another parent directory under the feasibility root.
     *
     * Tooling-only surface for stage-0 SAF move evidence (not production SAF policy).
     */
    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        val source = resolve(sourceDocumentId)
        if (!source.exists()) {
            throw FileNotFoundException(sourceDocumentId)
        }
        val sourceParent = resolve(sourceParentDocumentId)
        if (source.parentFile?.canonicalFile != sourceParent.canonicalFile) {
            throw FileNotFoundException(
                "source parent mismatch for $sourceDocumentId under $sourceParentDocumentId",
            )
        }
        val targetParent = resolve(targetParentDocumentId)
        if (!targetParent.isDirectory && !targetParent.mkdirs()) {
            throw FileNotFoundException("unable to create target parent $targetParentDocumentId")
        }
        val target = File(targetParent, source.name)
        if (target.exists()) {
            throw FileNotFoundException("move target already exists: ${toDocumentId(target)}")
        }
        if (!source.renameTo(target)) {
            // Same-filesystem rename should succeed under app private storage; fail closed.
            throw FileNotFoundException("unable to move $sourceDocumentId")
        }
        return toDocumentId(target)
    }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean = documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    private fun singleDocumentCursor(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(columns)
        appendDocument(cursor, columns, documentId)
        return cursor
    }

    private fun appendDocument(
        cursor: MatrixCursor,
        columns: Array<out String>,
        documentId: String,
    ) {
        val file = resolve(documentId)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(documentId)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME ->
                    row.add(if (documentId == ROOT_DOC_ID) "saf-root" else file.name)
                DocumentsContract.Document.COLUMN_MIME_TYPE ->
                    row.add(
                        if (file.isDirectory || documentId == ROOT_DOC_ID) {
                            DocumentsContract.Document.MIME_TYPE_DIR
                        } else {
                            "text/plain"
                        },
                    )
                DocumentsContract.Document.COLUMN_FLAGS ->
                    row.add(
                        DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                            DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                            DocumentsContract.Document.FLAG_SUPPORTS_MOVE or
                            if (file.isDirectory || documentId == ROOT_DOC_ID) {
                                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                            } else {
                                0
                            },
                    )
                DocumentsContract.Document.COLUMN_SIZE ->
                    row.add(if (file.isFile) file.length() else null)
                DocumentsContract.Document.COLUMN_LAST_MODIFIED ->
                    row.add(if (file.exists()) file.lastModified() else null)
                else -> row.add(null)
            }
        }
    }

    private fun rootDir(): File {
        val context = context ?: error("provider context missing")
        val root = File(context.filesDir, "saf-root")
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    private fun resolve(documentId: String): File {
        val root = rootDir()
        if (documentId == ROOT_DOC_ID) {
            return root
        }
        require(documentId.startsWith("$ROOT_DOC_ID/")) {
            "document id escapes feasibility root: $documentId"
        }
        val relative = documentId.removePrefix("$ROOT_DOC_ID/")
        require(!relative.contains("..")) { "path escape rejected: $documentId" }
        return File(root, relative)
    }

    private fun toDocumentId(file: File): String {
        val root = rootDir().canonicalFile
        val canonical = file.canonicalFile
        if (canonical == root) {
            return ROOT_DOC_ID
        }
        val relative = canonical.relativeTo(root).invariantSeparatorsPath
        return "$ROOT_DOC_ID/$relative"
    }

    companion object {
        const val AUTHORITY = "com.lomo.nativesmoke.documents"
        const val ROOT_ID = "feasibility-root"
        const val ROOT_DOC_ID = "root"

        private val DEFAULT_ROOT_PROJECTION =
            arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_MIME_TYPES,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            )

        private val DEFAULT_DOCUMENT_PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
    }
}
