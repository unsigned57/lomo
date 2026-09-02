package com.lomo.data.engine

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Production [PlatformDocumentsGateway] over [ContentResolver] / DocumentsContract.
 *
 * Tree URI strings come from [CapabilityRegistry]; conversion to [Uri] stays inside this edge.
 */
internal class ContentResolverPlatformDocumentsGateway(
    private val contentResolver: ContentResolver,
) : PlatformDocumentsGateway {
    override fun stat(
        treeUri: String,
        target: WorkspaceTarget,
    ): PlatformDocumentSnapshot? {
        val root = treeUri.toAndroidUri()
        return when (target) {
            is WorkspaceTarget.Root -> {
                val docId = DocumentsContract.getTreeDocumentId(root)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(root, docId)
                querySnapshot(
                    documentUri = documentUri,
                    target = WorkspaceTarget.Root,
                    documentId = docId,
                    digestMode = SnapshotDigestMode.CONTENT,
                )
            }
            is WorkspaceTarget.Relative -> {
                val resolved = resolvePath(root, target.path) ?: return null
                querySnapshot(
                    documentUri = resolved.uri,
                    target = target,
                    documentId = resolved.documentId,
                    digestMode = SnapshotDigestMode.CONTENT,
                )
            }
        }
    }

    override fun listChildren(
        treeUri: String,
        target: WorkspaceTarget,
        cursor: String?,
        pageSize: UInt,
    ): PlatformMetadataPage {
        val root = treeUri.toAndroidUri()
        val parentDocId =
            when (target) {
                is WorkspaceTarget.Root -> DocumentsContract.getTreeDocumentId(root)
                is WorkspaceTarget.Relative ->
                    resolvePath(root, target.path)?.documentId
                        ?: return PlatformMetadataPage(items = emptyList(), nextCursor = null)
            }
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(root, parentDocId)
        val items = ArrayList<PlatformDocumentSnapshot>(pageSize.toInt().coerceAtMost(256))
        contentResolver
            .query(childUri, DOCUMENT_PROJECTION, null, null, null)
            ?.use { queryCursor ->
                val indices = DocumentColumnIndices.from(queryCursor)
                val skip = cursor?.toIntOrNull() ?: 0
                var rowsConsumed = 0
                var hasMoreRows = false
                if (queryCursor.seekToPosition(skip)) {
                    do {
                        rowsConsumed += 1
                        indices.extractChildSnapshot(queryCursor, target)?.let { items += it }
                        if (items.size >= pageSize.toInt()) {
                            hasMoreRows = queryCursor.moveToNext()
                            break
                        }
                    } while (queryCursor.moveToNext())
                }
                val nextCursor =
                    if (hasMoreRows) {
                        (skip + rowsConsumed).toString()
                    } else {
                        null
                    }
                return PlatformMetadataPage(items = items, nextCursor = nextCursor)
            }
        return PlatformMetadataPage(items = items, nextCursor = null)
    }

    override fun ensureDirectory(
        treeUri: String,
        path: String,
    ): PlatformDocumentSnapshot {
        val root = treeUri.toAndroidUri()
        val segments = path.split('/').filter(String::isNotEmpty)
        require(segments.isNotEmpty()) { "directory path must not be empty" }
        var parentDocId = DocumentsContract.getTreeDocumentId(root)
        for (segment in segments) {
            val existing = findChild(root, parentDocId, segment)
            if (existing != null) {
                parentDocId = existing.documentId
                continue
            }
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(root, parentDocId)
            val created =
                DocumentsContract.createDocument(
                    contentResolver,
                    parentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    segment,
                ) ?: throw IOException("Failed to create directory segment $segment")
            parentDocId = DocumentsContract.getDocumentId(created)
        }
        return stat(treeUri, WorkspaceTarget.Relative(path))
            ?: throw IOException("Created directory is not observable: $path")
    }

    override fun openRead(
        treeUri: String,
        path: String,
    ): PlatformReadHandle {
        val root = treeUri.toAndroidUri()
        val resolved =
            resolvePath(root, path)
                ?: errorIo("Missing document: $path")
        val snapshot =
            querySnapshot(
                documentUri = resolved.uri,
                target = WorkspaceTarget.Relative(path),
                documentId = resolved.documentId,
                digestMode = SnapshotDigestMode.METADATA_ONLY,
            )
                ?: errorIo("Missing document metadata: $path")
        val bytes =
            contentResolver.openInputStream(resolved.uri)?.use { input -> input.readBytes() }
                ?: errorIo("openInputStream returned null for $path")
        return PlatformReadHandle(
            snapshot =
                snapshot.copy(
                    digest = bytes.sha256Hex(),
                    length = bytes.size.toULong(),
                ),
            bytes = bytes,
        )
    }

    override fun openReadByHandle(
        treeUri: String,
        path: String,
        documentHandle: String,
    ): PlatformReadHandle {
        val root = treeUri.toAndroidUri()
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(root, documentHandle)
        val snapshot =
            querySnapshot(
                documentUri = documentUri,
                target = WorkspaceTarget.Relative(path),
                documentId = documentHandle,
                digestMode = SnapshotDigestMode.METADATA_ONLY,
            ) ?: errorIo("Missing document metadata for opaque handle")
        val bytes =
            contentResolver.openInputStream(documentUri)?.use { input -> input.readBytes() }
                ?: errorIo("openInputStream returned null for opaque document handle")
        return PlatformReadHandle(
            snapshot = snapshot.copy(digest = bytes.sha256Hex(), length = bytes.size.toULong()),
            bytes = bytes,
        )
    }

    override fun writeFromExchange(
        treeUri: String,
        path: String,
        bytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot =
        writeToDocument(treeUri, path, ByteArrayInputStream(bytes), mode, mimeType)

    override fun writeFromFile(
        treeUri: String,
        path: String,
        source: File,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot =
        writeToDocument(treeUri, path, source.inputStream().buffered(), mode, mimeType)

    /**
     * One write law for every source: repair the created document onto the requested display
     * name, stream the payload chunk-wise, hash the streamed bytes while writing, verify the
     * persisted document by streaming readback digest, and roll back a document this write
     * created when any step fails. Memory stays bounded by [WRITE_CHUNK_BYTES].
     */
    private fun writeToDocument(
        treeUri: String,
        path: String,
        source: InputStream,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot {
        val root = treeUri.toAndroidUri()
        val existing = resolvePath(root, path)
        val target = resolveWriteTarget(root, treeUri, path, mode, existing, mimeType)
        var targetUri = target.uri
        try {
            if (target.createdByThisWrite) {
                targetUri =
                    repairDisplayName(
                        root = root,
                        documentUri = target.uri,
                        parentDocId = resolveParentDocId(root, path),
                        requestedName = path.substringAfterLast('/'),
                        description = path,
                    )
            }
            val writtenDigest = MessageDigest.getInstance(DIGEST_ALGORITHM)
            contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
                val buffer = ByteArray(WRITE_CHUNK_BYTES)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    writtenDigest.update(buffer, 0, read)
                }
            } ?: errorIo("openOutputStream returned null for $path")
            val writtenDigestHex = writtenDigest.finishSha256Hex()
            val (persistedDigest, persistedLength) =
                contentResolver.openInputStream(targetUri)?.use { input ->
                    val readbackDigest = MessageDigest.getInstance(DIGEST_ALGORITHM)
                    val buffer = ByteArray(WRITE_CHUNK_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        readbackDigest.update(buffer, 0, read)
                        total += read
                    }
                    readbackDigest.finishSha256Hex() to total
                } ?: errorIo("Written document cannot be reopened for verification: $path")
            if (persistedDigest != writtenDigestHex) {
                errorIo("Written document readback does not match requested bytes: $path")
            }
            val documentId = DocumentsContract.getDocumentId(targetUri)
            return querySnapshot(
                documentUri = targetUri,
                target = WorkspaceTarget.Relative(path),
                documentId = documentId,
                digestMode = SnapshotDigestMode.METADATA_ONLY,
            )
                ?.copy(
                    digest = persistedDigest,
                    length = persistedLength.toULong(),
                )
                ?: errorIo("Written document is not observable: $path")
        } catch (failure: Exception) {
            if (target.createdByThisWrite) {
                try {
                    if (!DocumentsContract.deleteDocument(contentResolver, targetUri)) {
                        failure.addSuppressed(
                            IOException("Incomplete created document could not be deleted: $path"),
                        )
                    }
                } catch (rollbackFailure: Exception) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        } finally {
            source.close()
        }
    }

    private fun resolveWriteTarget(
        root: Uri,
        treeUri: String,
        path: String,
        mode: WriteMode,
        existing: ResolvedDocument?,
        mimeType: String?,
    ): WriteTarget =
        when {
            existing != null && mode == WriteMode.CREATE ->
                errorIo("Create refused over existing path: $path")
            existing != null -> WriteTarget(existing.uri, createdByThisWrite = false)
            else ->
                WriteTarget(
                    uri = createFile(root, treeUri, path, mimeType ?: "application/octet-stream"),
                    createdByThisWrite = true,
                )
        }

    override fun move(
        treeUri: String,
        source: String,
        target: String,
    ): PlatformDocumentSnapshot {
        val root = treeUri.toAndroidUri()
        val sourceResolved = resolvePath(root, source) ?: errorIo("Missing source: $source")
        val targetParentPath = target.substringBeforeLast('/', missingDelimiterValue = "")
        val targetName = target.substringAfterLast('/')
        val parentDocId =
            if (targetParentPath.isEmpty()) {
                DocumentsContract.getTreeDocumentId(root)
            } else {
                ensureDirectory(treeUri, targetParentPath).documentId
            }
        val sourceParentDocId = resolveParentDocId(root, source)
        val targetExisting = resolvePath(root, target)
        if (targetExisting != null && targetExisting.uri != sourceResolved.uri) {
            DocumentsContract.deleteDocument(contentResolver, targetExisting.uri)
        }
        val targetDocUri =
            if (sourceParentDocId == parentDocId) {
                sourceResolved.uri
            } else {
                DocumentsContract.moveDocument(
                    contentResolver,
                    sourceResolved.uri,
                    DocumentsContract.buildDocumentUriUsingTree(root, sourceParentDocId),
                    DocumentsContract.buildDocumentUriUsingTree(root, parentDocId),
                ) ?: errorIo("moveDocument returned null for $source -> $target")
            }
        repairDisplayName(
            root = root,
            documentUri = targetDocUri,
            parentDocId = parentDocId,
            requestedName = targetName,
            description = target,
        )
        return stat(treeUri, WorkspaceTarget.Relative(target))
            ?: errorIo("Moved document is not observable: $target")
    }

    private fun resolveParentDocId(
        root: Uri,
        path: String,
    ): String {
        val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
        return if (parentPath.isEmpty()) {
            DocumentsContract.getTreeDocumentId(root)
        } else {
            resolvePath(root, parentPath)?.documentId
                ?: errorIo("Missing source parent: $parentPath")
        }
    }

    private fun repairDisplayName(
        root: Uri,
        documentUri: Uri,
        parentDocId: String,
        requestedName: String,
        description: String,
    ): Uri {
        val initialName = queryDisplayName(documentUri)
        if (initialName == null || initialName == requestedName) return documentUri
        val existing = findChild(root, parentDocId, requestedName)
        if (existing != null) {
            errorIo("Cannot repair display name: $requestedName is occupied")
        }
        val renamed =
            DocumentsContract.renameDocument(contentResolver, documentUri, requestedName)
                ?: errorIo("rename failed for $description")
        val finalName = queryDisplayName(renamed)
        if (finalName != requestedName) {
            errorIo("rename did not honor requested display name for $description: got $finalName")
        }
        return renamed
    }


    override fun delete(
        treeUri: String,
        path: String,
    ) {
        val root = treeUri.toAndroidUri()
        val resolved = resolvePath(root, path) ?: return
        DocumentsContract.deleteDocument(contentResolver, resolved.uri)
    }

    private fun createFile(
        root: Uri,
        treeUri: String,
        path: String,
        mimeType: String,
    ): Uri {
        val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
        val name = path.substringAfterLast('/')
        val parentDocId =
            if (parentPath.isEmpty()) {
                DocumentsContract.getTreeDocumentId(root)
            } else {
                ensureDirectory(treeUri, parentPath).documentId
            }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(root, parentDocId)
        return DocumentsContract.createDocument(contentResolver, parentUri, mimeType, name)
            ?: throw IOException("Failed to create file $path")
    }

    private fun resolvePath(
        root: Uri,
        path: String,
    ): ResolvedDocument? {
        var parentDocId = DocumentsContract.getTreeDocumentId(root)
        val segments = path.split('/').filter(String::isNotEmpty)
        if (segments.isEmpty()) return null
        var current: ResolvedDocument? = null
        for (segment in segments) {
            current = findChild(root, parentDocId, segment) ?: return null
            parentDocId = current.documentId
        }
        return current
    }

    private fun findChild(
        root: Uri,
        parentDocId: String,
        name: String,
    ): ResolvedDocument? {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(root, parentDocId)
        contentResolver
            .query(
                childUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == name) {
                        val documentId = cursor.getString(idIndex) ?: continue
                        return ResolvedDocument(
                            documentId = documentId,
                            uri = DocumentsContract.buildDocumentUriUsingTree(root, documentId),
                        )
                    }
                }
            }
        return null
    }

    private fun queryDisplayName(
        documentUri: Uri,
    ): String? {
        contentResolver
            .query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                return cursor.getString(0)
            }
        return null
    }

    private fun querySnapshot(
        documentUri: Uri,
        target: WorkspaceTarget,
        documentId: String,
        digestMode: SnapshotDigestMode,
    ): PlatformDocumentSnapshot? {
        contentResolver
            .query(documentUri, DOCUMENT_PROJECTION, null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                return snapshotFromCursor(cursor, target, documentId, documentUri, digestMode)
            }
        return null
    }

    private fun snapshotFromCursor(
        cursor: Cursor,
        target: WorkspaceTarget,
        documentId: String,
        documentUri: Uri,
        digestMode: SnapshotDigestMode,
    ): PlatformDocumentSnapshot {
        val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
        val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val mime = cursor.getString(mimeIndex)
        val kind =
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                DocumentKind.DIRECTORY
            } else {
                DocumentKind.FILE
            }
        val length =
            if (kind == DocumentKind.DIRECTORY) {
                0uL
            } else {
                cursor.getLong(sizeIndex).coerceAtLeast(0L).toULong()
            }
        val lastModified = cursor.getLong(modifiedIndex).coerceAtLeast(0L)
        val digest =
            if (kind == DocumentKind.FILE && digestMode == SnapshotDigestMode.CONTENT) {
                digestDocument(documentUri)
            } else {
                EMPTY_SHA256
            }
        return PlatformDocumentSnapshot(
            target = target,
            kind = kind,
            mimeType = mime?.takeUnless { it == DocumentsContract.Document.MIME_TYPE_DIR },
            length = length,
            lastModifiedEpochMillis = lastModified,
            documentId = documentId,
            digest = digest,
        )
    }

    private fun digestDocument(documentUri: Uri): String =
        contentResolver.openInputStream(documentUri)?.use { input -> input.sha256Hex() }
            ?: EMPTY_SHA256

    private data class ResolvedDocument(
        val documentId: String,
        val uri: Uri,
    )

    private data class WriteTarget(
        val uri: Uri,
        val createdByThisWrite: Boolean,
    )

    private enum class SnapshotDigestMode {
        METADATA_ONLY,
        CONTENT,
    }

    internal companion object {
        val DOCUMENT_PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
        val EMPTY_SHA256 = ByteArray(0).sha256Hex()

        private const val DIGEST_ALGORITHM = "SHA-256"

        private const val WRITE_CHUNK_BYTES = 64 * 1024
    }
}

private data class DocumentColumnIndices(
    val idIndex: Int,
    val nameIndex: Int,
    val mimeIndex: Int,
    val sizeIndex: Int,
    val modifiedIndex: Int,
) {
    fun extractChildSnapshot(cursor: Cursor, target: WorkspaceTarget): PlatformDocumentSnapshot? {
        val name = cursor.getString(nameIndex) ?: return null
        val documentId = cursor.getString(idIndex) ?: return null
        val childPath =
            when (target) {
                is WorkspaceTarget.Root -> name
                is WorkspaceTarget.Relative -> "${target.path}/$name"
            }
        val mime = cursor.getString(mimeIndex)
        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
        val kind = if (isDir) DocumentKind.DIRECTORY else DocumentKind.FILE
        val length = if (isDir) 0uL else cursor.getLong(sizeIndex).coerceAtLeast(0L).toULong()
        val lastModified = cursor.getLong(modifiedIndex).coerceAtLeast(0L)
        return PlatformDocumentSnapshot(
            target = WorkspaceTarget.Relative(childPath),
            kind = kind,
            mimeType = mime?.takeUnless { isDir },
            length = length,
            lastModifiedEpochMillis = lastModified,
            documentId = documentId,
            digest = ContentResolverPlatformDocumentsGateway.EMPTY_SHA256,
        )
    }

    companion object {
        fun from(cursor: Cursor): DocumentColumnIndices =
            DocumentColumnIndices(
                idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE),
                sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE),
                modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            )
    }
}

private fun errorIo(message: String): Nothing = throw IOException(message)

private fun Cursor.seekToPosition(skip: Int): Boolean =
    try {
        moveToPosition(skip)
    } catch (_: Exception) {
        var advanced = 0
        var hasMore = false
        while (advanced <= skip && moveToNext()) {
            advanced += 1
            if (advanced > skip) {
                hasMore = true
                break
            }
        }
        hasMore
    }
