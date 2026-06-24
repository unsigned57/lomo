package com.lomo.data.repository

import android.content.Context
import android.net.Uri
import android.database.Cursor
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.lomo.data.util.md5Hex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

data class S3SafTreeFile(
    val relativePath: String,
    val lastModified: Long,
    val size: Long? = null,
    val documentUri: String? = null,
    val documentId: String? = null,
)

interface S3SafTreeAccess {
    suspend fun listFiles(rootUriString: String): List<S3SafTreeFile>

    suspend fun getFile(
        rootUriString: String,
        relativePath: String,
    ): S3SafTreeFile?

    suspend fun readBytes(
        rootUriString: String,
        relativePath: String,
    ): ByteArray?

    suspend fun readText(
        rootUriString: String,
        relativePath: String,
    ): String?

    suspend fun md5Hex(
        rootUriString: String,
        relativePath: String,
    ): String?

    suspend fun exportToFile(
        rootUriString: String,
        relativePath: String,
        destination: File,
    ): Boolean

    suspend fun writeBytes(
        rootUriString: String,
        relativePath: String,
        bytes: ByteArray,
    )

    suspend fun importFromFile(
        rootUriString: String,
        relativePath: String,
        source: File,
    )

    suspend fun deleteFile(
        rootUriString: String,
        relativePath: String,
    )
}

@Singleton
class AndroidS3SafTreeAccess
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : S3SafTreeAccess {
        private val uriCache = java.util.concurrent.ConcurrentHashMap<String, UriCacheEntry>()

        private data class UriCacheEntry(
            val documentUri: String,
            val documentId: String,
        )

        private fun cacheKey(rootUriString: String, relativePath: String): String =
            "$rootUriString:${sanitizeRelativePath(relativePath) ?: relativePath}"

        private fun listS3SafFiles(
            rootUriString: String,
            rootUri: Uri,
            rootDocId: String,
        ): List<S3SafTreeFile> {
            val results = mutableListOf<S3SafTreeFile>()
            val queue = ArrayDeque<Pair<String, String>>().apply { add(rootDocId to "") }
            while (queue.isNotEmpty()) {
                val (parentDocId, prefix) = queue.removeFirst()
                val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
                context.contentResolver.query(
                    childUri,
                    S3_SAF_LIST_PROJECTION,
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    processS3SafListCursor(
                        rootUriString = rootUriString,
                        rootUri = rootUri,
                        prefix = prefix,
                        queue = queue,
                        cursor = cursor,
                        results = results,
                    )
                }
            }
            return results
        }

        private fun processS3SafListCursor(
            rootUriString: String,
            rootUri: Uri,
            prefix: String,
            queue: ArrayDeque<Pair<String, String>>,
            cursor: Cursor,
            results: MutableList<S3SafTreeFile>,
        ) {
            val docIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val lastModifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                processS3SafListRow(
                    rootUriString = rootUriString,
                    rootUri = rootUri,
                    prefix = prefix,
                    queue = queue,
                    cursor = cursor,
                    indexes =
                        S3SafListCursorIndexes(
                            documentId = docIdIndex,
                            name = nameIndex,
                            mime = mimeIndex,
                            lastModified = lastModifiedIndex,
                            size = sizeIndex,
                        ),
                    results = results,
                )
            }
        }

        private fun processS3SafListRow(
            rootUriString: String,
            rootUri: Uri,
            prefix: String,
            queue: ArrayDeque<Pair<String, String>>,
            cursor: Cursor,
            indexes: S3SafListCursorIndexes,
            results: MutableList<S3SafTreeFile>,
        ) {
            val documentId = cursor.getStringOrNull(indexes.documentId)
            val name = cursor.getStringOrNull(indexes.name)
            if (documentId == null || name == null || name.startsWith(".")) return
            val isDirectory = cursor.getStringOrNull(indexes.mime) == DocumentsContract.Document.MIME_TYPE_DIR
            val relativePath = joinRelativePath(prefix, name)
            if (isDirectory) {
                queue.add(documentId to relativePath)
            } else {
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId).toString()
                uriCache[cacheKey(rootUriString, relativePath)] = UriCacheEntry(documentUri, documentId)
                results +=
                    S3SafTreeFile(
                        relativePath = relativePath,
                        lastModified = cursor.getLongOrZero(indexes.lastModified),
                        size = cursor.getLongOrNull(indexes.size),
                        documentUri = documentUri,
                        documentId = documentId,
                    )
            }
        }

        override suspend fun listFiles(rootUriString: String): List<S3SafTreeFile> =
            withContext(Dispatchers.IO) {
                val rootUri = rootUriString.toUri()
                val rootDocId = try {
                    DocumentsContract.getTreeDocumentId(rootUri)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to get tree document ID")
                    return@withContext emptyList()
                }
                listS3SafFiles(rootUriString = rootUriString, rootUri = rootUri, rootDocId = rootDocId)
            }

        override suspend fun getFile(
            rootUriString: String,
            relativePath: String,
        ): S3SafTreeFile? =
            withContext(Dispatchers.IO) {
                val entry = resolvePathToUriAndId(rootUriString, relativePath) ?: return@withContext null
                val docUri = entry.documentUri.toUri()
                try {
                    context.contentResolver.query(
                        docUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                            DocumentsContract.Document.COLUMN_SIZE
                        ),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val timeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                            val lastModified = cursor.getLongOrZero(timeIdx)
                            val size = cursor.getLongOrNull(sizeIdx)
                            S3SafTreeFile(
                                relativePath = sanitizeRelativePath(relativePath) ?: relativePath,
                                lastModified = lastModified,
                                size = size,
                                documentUri = entry.documentUri,
                                documentId = entry.documentId
                            )
                        } else null
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to get file: %s", relativePath)
                    uriCache.remove(cacheKey(rootUriString, relativePath))
                    null
                }
            }

        override suspend fun readBytes(
            rootUriString: String,
            relativePath: String,
        ): ByteArray? =
            withContext(Dispatchers.IO) {
                val entry = resolvePathToUriAndId(rootUriString, relativePath) ?: return@withContext null
                try {
                    context.contentResolver.openInputStream(entry.documentUri.toUri())?.use { it.readBytes() }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to read bytes: %s", relativePath)
                    uriCache.remove(cacheKey(rootUriString, relativePath))
                    null
                }
            }

        override suspend fun readText(
            rootUriString: String,
            relativePath: String,
        ): String? = readBytes(rootUriString, relativePath)?.toString(StandardCharsets.UTF_8)

        override suspend fun md5Hex(
            rootUriString: String,
            relativePath: String,
        ): String? =
            withContext(Dispatchers.IO) {
                val entry = resolvePathToUriAndId(rootUriString, relativePath) ?: return@withContext null
                try {
                    val input =
                        context.contentResolver.openInputStream(entry.documentUri.toUri())
                            ?: return@withContext null
                    input.use { stream -> stream.md5Hex() }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to fingerprint file: %s", relativePath)
                    uriCache.remove(cacheKey(rootUriString, relativePath))
                    null
                }
            }

        override suspend fun exportToFile(
            rootUriString: String,
            relativePath: String,
            destination: File,
        ): Boolean =
            withContext(Dispatchers.IO) {
                val entry = resolvePathToUriAndId(rootUriString, relativePath) ?: return@withContext false
                destination.parentFile?.mkdirs()
                try {
                    context.contentResolver.openInputStream(entry.documentUri.toUri())?.use { input ->
                        destination.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: return@withContext false
                    true
                } catch (e: Exception) {
                    Timber.w(e, "Failed to export file: %s", relativePath)
                    uriCache.remove(cacheKey(rootUriString, relativePath))
                    false
                }
            }

        override suspend fun writeBytes(
            rootUriString: String,
            relativePath: String,
            bytes: ByteArray,
        ) {
            withContext(Dispatchers.IO) {
                val entry = getOrCreateFileEntry(rootUriString, relativePath)
                context.contentResolver.openOutputStream(entry.documentUri.toUri(), "w")?.use { output ->
                    output.write(bytes)
                } ?: throw IOException("Cannot open SAF output stream: $relativePath")
            }
        }

        override suspend fun importFromFile(
            rootUriString: String,
            relativePath: String,
            source: File,
        ) {
            withContext(Dispatchers.IO) {
                val entry = getOrCreateFileEntry(rootUriString, relativePath)
                context.contentResolver.openOutputStream(entry.documentUri.toUri(), "w")?.use { output ->
                    source.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Cannot open SAF output stream: $relativePath")
            }
        }

        override suspend fun deleteFile(
            rootUriString: String,
            relativePath: String,
        ) {
            withContext(Dispatchers.IO) {
                val entry = resolvePathToUriAndId(rootUriString, relativePath) ?: return@withContext
                try {
                    DocumentsContract.deleteDocument(context.contentResolver, entry.documentUri.toUri())
                } catch (e: Exception) {
                    Timber.w(e, "Failed to delete document: %s", relativePath)
                } finally {
                    uriCache.remove(cacheKey(rootUriString, relativePath))
                }
            }
        }

        private suspend fun resolvePathToUriAndId(
            rootUriString: String,
            relativePath: String
        ): UriCacheEntry? {
            val sanitized = sanitizeRelativePath(relativePath) ?: return null
            val key = cacheKey(rootUriString, sanitized)
            uriCache[key]?.let { return it }

            val rootUri = rootUriString.toUri()
            var currentDocId = try {
                DocumentsContract.getTreeDocumentId(rootUri)
            } catch (e: Exception) {
                Timber.w(e, "Failed to get tree document ID: %s", rootUriString)
                return null
            }

            val segments = sanitized.split('/').filter { it.isNotEmpty() }
            var currentPrefix = ""

            for (i in segments.indices) {
                val segment = segments[i]
                val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, currentDocId)
                var foundEntry: UriCacheEntry? = null

                context.contentResolver.query(
                    childUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ),
                    null, null, null
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val docId = cursor.getStringOrNull(idIdx)
                        val name = cursor.getStringOrNull(nameIdx)
                        if (docId != null && name == segment) {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId).toString()
                            foundEntry = UriCacheEntry(fileUri, docId)
                            break
                        }
                    }
                }

                val entry = foundEntry ?: return null
                currentDocId = entry.documentId
                currentPrefix = if (currentPrefix.isEmpty()) segment else "$currentPrefix/$segment"
                uriCache[cacheKey(rootUriString, currentPrefix)] = entry
            }

            return uriCache[key]
        }

        private fun ensureDirectoryDocId(
            rootUriString: String,
            parentPath: String
        ): String {
            if (parentPath.isBlank()) {
                val rootUri = rootUriString.toUri()
                return DocumentsContract.getTreeDocumentId(rootUri)
            }
            
            val sanitized = sanitizeRelativePath(parentPath) ?: parentPath
            val cacheKey = cacheKey(rootUriString, sanitized)
            uriCache[cacheKey]?.let { return it.documentId }

            val rootUri = rootUriString.toUri()
            var currentDocId = DocumentsContract.getTreeDocumentId(rootUri)
            val segments = sanitized.split('/').filter { it.isNotEmpty() }
            var currentPrefix = ""

            for (segment in segments) {
                val nextPrefix = if (currentPrefix.isEmpty()) segment else "$currentPrefix/$segment"
                val nextKey = cacheKey(rootUriString, nextPrefix)
                val cached = uriCache[nextKey]
                if (cached != null) {
                    currentDocId = cached.documentId
                    currentPrefix = nextPrefix
                    continue
                }

                val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, currentDocId)
                var foundDocId: String? = null
                context.contentResolver.query(
                    childUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ),
                    null, null, null
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val docId = cursor.getStringOrNull(idIdx)
                        val name = cursor.getStringOrNull(nameIdx)
                        if (docId != null && name == segment) {
                            foundDocId = docId
                            break
                        }
                    }
                }

                if (foundDocId == null) {
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, currentDocId)
                    val createdUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        parentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        segment
                    ) ?: throw IOException("Failed to create SAF directory segment '$segment' for $parentPath")
                    
                    val createdDocId = DocumentsContract.getDocumentId(createdUri)
                    foundDocId = createdDocId
                }

                val docId = foundDocId!!
                val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId).toString()
                val entry = UriCacheEntry(docUri, docId)
                uriCache[nextKey] = entry
                
                currentDocId = docId
                currentPrefix = nextPrefix
            }

            return currentDocId
        }

        private fun getOrCreateFileEntry(
            rootUriString: String,
            relativePath: String
        ): UriCacheEntry {
            val sanitized = sanitizeRelativePath(relativePath) ?: relativePath
            val key = cacheKey(rootUriString, sanitized)
            uriCache[key]?.let { return it }

            val parentPath = sanitized.substringBeforeLast('/', "")
            val filename = sanitized.substringAfterLast('/')
            val parentDocId = ensureDirectoryDocId(rootUriString, parentPath)

            val rootUri = rootUriString.toUri()
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, parentDocId)

            var existingEntry: UriCacheEntry? = null
            val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
            context.contentResolver.query(
                childUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val docId = cursor.getStringOrNull(idIdx)
                    val name = cursor.getStringOrNull(nameIdx)
                    val mime = cursor.getStringOrNull(mimeIdx)
                    if (docId != null && name == filename) {
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                            try {
                                DocumentsContract.deleteDocument(context.contentResolver, docUri)
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to delete directory: %s", docUri)
                            }
                        } else {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId).toString()
                            existingEntry = UriCacheEntry(fileUri, docId)
                            uriCache[key] = existingEntry
                            break
                        }
                    }
                }
            }

            if (existingEntry == null) {
                val createdUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    contentTypeForRelativePath(sanitized),
                    filename
                ) ?: throw IOException("Cannot create SAF file: $sanitized")
                val createdDocId = DocumentsContract.getDocumentId(createdUri)
                val entry = UriCacheEntry(createdUri.toString(), createdDocId)
                uriCache[key] = entry
                return entry
            }

            return existingEntry
        }
    }

internal object UnsupportedS3SafTreeAccess : S3SafTreeAccess {
    override suspend fun listFiles(rootUriString: String): List<S3SafTreeFile> =
        error("S3 SAF tree access is not configured for this test instance")

    override suspend fun getFile(
        rootUriString: String,
        relativePath: String,
    ): S3SafTreeFile? = error("S3 SAF tree access is not configured for this test instance")

    override suspend fun readBytes(
        rootUriString: String,
        relativePath: String,
    ): ByteArray? = error("S3 SAF tree access is not configured for this test instance")

    override suspend fun readText(
        rootUriString: String,
        relativePath: String,
    ): String? = error("S3 SAF tree access is not configured for this test instance")

    override suspend fun md5Hex(
        rootUriString: String,
        relativePath: String,
    ): String? = error("S3 SAF tree access is not configured for this test instance")

    override suspend fun exportToFile(
        rootUriString: String,
        relativePath: String,
        destination: File,
    ): Boolean = error("S3 SAF tree access is not configured for this test instance")

    override suspend fun writeBytes(
        rootUriString: String,
        relativePath: String,
        bytes: ByteArray,
    ) {
        error("S3 SAF tree access is not configured for this test instance")
    }

    override suspend fun importFromFile(
        rootUriString: String,
        relativePath: String,
        source: File,
    ) {
        error("S3 SAF tree access is not configured for this test instance")
    }

    override suspend fun deleteFile(
        rootUriString: String,
        relativePath: String,
    ) {
        error("S3 SAF tree access is not configured for this test instance")
    }
}

private fun Cursor.getStringOrNull(index: Int): String? =
    if (index != -1) getString(index) else null

private fun Cursor.getLongOrZero(index: Int): Long =
    if (index != -1) getLong(index) else 0L

private fun Cursor.getLongOrNull(index: Int): Long? =
    if (index != -1 && !isNull(index)) getLong(index) else null

private data class S3SafListCursorIndexes(
    val documentId: Int,
    val name: Int,
    val mime: Int,
    val lastModified: Int,
    val size: Int,
)

private val S3_SAF_LIST_PROJECTION =
    arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )
