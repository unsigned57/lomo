package com.lomo.data.engine

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import java.io.IOException

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
                querySnapshot(documentUri, WorkspaceTarget.Root, docId)
            }
            is WorkspaceTarget.Relative -> {
                val resolved = resolvePath(root, target.path) ?: return null
                querySnapshot(resolved.uri, target, resolved.documentId)
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
                val idIndex = queryCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = queryCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = queryCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = queryCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex =
                    queryCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                var skipped = 0
                val skip = cursor?.toIntOrNull() ?: 0
                while (queryCursor.moveToNext() && items.size < pageSize.toInt()) {
                    if (skipped < skip) {
                        skipped += 1
                    } else {
                        val name = queryCursor.getString(nameIndex)
                        val documentId = queryCursor.getString(idIndex)
                        if (name != null && documentId != null) {
                        val childPath =
                            when (target) {
                                is WorkspaceTarget.Root -> name
                                is WorkspaceTarget.Relative -> "${target.path}/$name"
                            }
                        val mime = queryCursor.getString(mimeIndex)
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
                                queryCursor.getLong(sizeIndex).coerceAtLeast(0L).toULong()
                            }
                        val lastModified = queryCursor.getLong(modifiedIndex).coerceAtLeast(0L)
                        val digest =
                            if (kind == DocumentKind.FILE) {
                                val docUri = DocumentsContract.buildDocumentUriUsingTree(root, documentId)
                                digestDocument(docUri)
                            } else {
                                EMPTY_SHA256
                            }
                        items +=
                            PlatformDocumentSnapshot(
                                target = WorkspaceTarget.Relative(childPath),
                                kind = kind,
                                mimeType =
                                    mime?.takeUnless {
                                        it == DocumentsContract.Document.MIME_TYPE_DIR
                                    },
                                length = length,
                                lastModifiedEpochMillis = lastModified,
                                documentId = documentId,
                                digest = digest,
                            )
                        }
                    }
                }
                val nextCursor =
                    if (items.size >= pageSize.toInt() && queryCursor.moveToNext()) {
                        (skip + items.size).toString()
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
            querySnapshot(resolved.uri, WorkspaceTarget.Relative(path), resolved.documentId)
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

    override fun writeFromExchange(
        treeUri: String,
        path: String,
        bytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot {
        val root = treeUri.toAndroidUri()
        val existing = resolvePath(root, path)
        val targetUri = resolveWriteTargetUri(root, treeUri, path, mode, existing, mimeType)
        contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
            output.write(bytes)
        } ?: errorIo("openOutputStream returned null for $path")
        val documentId = DocumentsContract.getDocumentId(targetUri)
        return querySnapshot(targetUri, WorkspaceTarget.Relative(path), documentId)
            ?.copy(digest = bytes.sha256Hex(), length = bytes.size.toULong())
            ?: errorIo("Written document is not observable: $path")
    }

    private fun resolveWriteTargetUri(
        root: Uri,
        treeUri: String,
        path: String,
        mode: WriteMode,
        existing: ResolvedDocument?,
        mimeType: String?,
    ): Uri =
        when {
            existing != null && mode == WriteMode.CREATE ->
                errorIo("Create refused over existing path: $path")
            existing != null -> existing.uri
            else -> createFile(root, treeUri, path, mimeType ?: "application/octet-stream")
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
        val moved =
            DocumentsContract.moveDocument(
                contentResolver,
                sourceResolved.uri,
                DocumentsContract.buildDocumentUriUsingTree(root, sourceParentDocId),
                DocumentsContract.buildDocumentUriUsingTree(root, parentDocId),
            ) ?: errorIo("moveDocument returned null for $source -> $target")
        renameIfNeeded(root, moved, targetName, target)
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

    private fun renameIfNeeded(
        root: Uri,
        moved: Uri,
        targetName: String,
        target: String,
    ) {
        val movedName = queryDisplayName(root, DocumentsContract.getDocumentId(moved))
        if (movedName != null && movedName != targetName) {
            DocumentsContract.renameDocument(contentResolver, moved, targetName)
                ?: errorIo("rename after move failed for $target")
        }
    }

    private fun errorIo(message: String): Nothing = throw IOException(message)

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
        root: Uri,
        documentId: String,
    ): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(root, documentId)
        contentResolver
            .query(
                uri,
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
    ): PlatformDocumentSnapshot? {
        contentResolver
            .query(documentUri, DOCUMENT_PROJECTION, null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                return snapshotFromCursor(cursor, target, documentId, documentUri)
            }
        return null
    }

    private fun snapshotFromCursor(
        cursor: Cursor,
        target: WorkspaceTarget,
        documentId: String,
        documentUri: Uri,
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
            if (kind == DocumentKind.FILE) {
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

    private companion object {
        val DOCUMENT_PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
        val EMPTY_SHA256 = ByteArray(0).sha256Hex()
    }
}
