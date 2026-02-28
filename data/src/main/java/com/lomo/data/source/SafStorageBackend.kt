package com.lomo.data.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Storage backend implementation using Storage Access Framework (SAF).
 * Handles content:// URIs obtained from the document picker.
 */
class SafStorageBackend(
    private val context: Context,
    private val rootUri: Uri,
    private val subDir: String? = null,
) : MarkdownStorageBackend,
    WorkspaceConfigBackend,
    MediaStorageBackend {
    private val safIoDispatcher = Dispatchers.IO.limitedParallelism(4)

    private val imageExtensions =
        setOf(
            "jpg",
            "jpeg",
            "png",
            "gif",
            "webp",
            "bmp",
            "heic",
            "heif",
            "avif",
        )

    private fun getRoot(): DocumentFile? {
        return try {
            val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
            if (subDir != null) {
                root.findFile(subDir) ?: root.createDirectory(subDir)
            } else {
                root
            }
        } catch (e: SecurityException) {
            timber.log.Timber.w(e, "Lost SAF permission for root uri: %s", rootUri)
            null
        }
    }

    private fun isImageFilename(name: String): Boolean {
        val extension = name.substringAfterLast('.', "")
        return extension.isNotBlank() && extension.lowercase() in imageExtensions
    }

    // Cache trash dir to avoid repeated findFile calls
    private var cachedTrashDir: DocumentFile? = null

    private fun invalidateDocumentCaches() {
        cachedTrashDir = null
    }

    private inline fun <T> withSecurityRetry(
        operation: String,
        fallbackValue: T,
        block: () -> T,
    ): T {
        repeat(SECURITY_RETRY_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (e: SecurityException) {
                invalidateDocumentCaches()
                val hasRetry = attempt + 1 < SECURITY_RETRY_ATTEMPTS
                if (hasRetry) {
                    timber.log.Timber.w(e, "%s hit SecurityException; retrying once", operation)
                } else {
                    timber.log.Timber.e(e, "%s hit SecurityException; giving up", operation)
                }
            }
        }
        return fallbackValue
    }

    private fun DocumentFile.isUsableSafDocument(): Boolean =
        try {
            exists() && canRead() && (isFile || isDirectory)
        } catch (_: SecurityException) {
            false
        }

    private fun getTrashDir(): DocumentFile? {
        cachedTrashDir?.let { cached ->
            if (cached.isUsableSafDocument()) {
                return cached
            }
            cachedTrashDir = null
        }

        val resolved =
            getRoot()
                ?.findFile(".trash")
                ?.takeIf { it.isUsableSafDocument() }
        cachedTrashDir = resolved
        return resolved
    }

    private fun getOrCreateTrashDir(): DocumentFile? {
        cachedTrashDir?.let { cached ->
            if (cached.isUsableSafDocument()) {
                return cached
            }
            cachedTrashDir = null
        }
        val root = getRoot() ?: return null
        val resolved =
            (root.findFile(".trash") ?: root.createDirectory(".trash"))
                ?.takeIf { it.isUsableSafDocument() }
        cachedTrashDir = resolved
        return resolved
    }

    private fun readFileFromUri(uri: Uri): String? =
        try {
            context.contentResolver.openInputStream(uri)?.use {
                BufferedReader(InputStreamReader(it)).readText()
            }
        } catch (e: Exception) {
            null
        }

    // --- Context-aware API ---

    override suspend fun listFilesIn(
        directory: MemoDirectoryType,
        targetFilename: String?,
    ): List<FileContent> =
        when (directory) {
            MemoDirectoryType.MAIN -> listFiles(targetFilename)
            MemoDirectoryType.TRASH -> listTrashFiles()
        }

    override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> =
        when (directory) {
            MemoDirectoryType.MAIN -> listMetadata()
            MemoDirectoryType.TRASH -> listTrashMetadata()
        }

    override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> =
        when (directory) {
            MemoDirectoryType.MAIN -> listMetadataWithIds()
            MemoDirectoryType.TRASH -> listTrashMetadataWithIds()
        }

    override suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): FileMetadata? =
        when (directory) {
            MemoDirectoryType.MAIN -> getFileMetadata(filename)
            MemoDirectoryType.TRASH -> getTrashFileMetadata(filename)
        }

    override suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> readFile(filename)
            MemoDirectoryType.TRASH -> readTrashFile(filename)
        }

    override suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> readFileByDocumentId(documentId)
            MemoDirectoryType.TRASH -> readTrashFileByDocumentId(documentId)
        }

    override suspend fun readHeadIn(
        directory: MemoDirectoryType,
        filename: String,
        maxChars: Int,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> readHead(filename, maxChars)
            MemoDirectoryType.TRASH -> readFileIn(MemoDirectoryType.TRASH, filename)?.take(maxChars)
        }

    override suspend fun readHeadByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
        maxChars: Int,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> readHeadByDocumentId(documentId, maxChars)
            MemoDirectoryType.TRASH -> readFileByDocumentIdIn(MemoDirectoryType.TRASH, documentId)?.take(maxChars)
        }

    override suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean,
        uri: Uri?,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> {
                saveFile(filename, content, append, uri)
            }

            MemoDirectoryType.TRASH -> {
                saveTrashFile(filename, content, append)
                null
            }
        }

    override suspend fun deleteFileIn(
        directory: MemoDirectoryType,
        filename: String,
        uri: Uri?,
    ) {
        when (directory) {
            MemoDirectoryType.MAIN -> deleteFile(filename, uri)
            MemoDirectoryType.TRASH -> deleteTrashFile(filename)
        }
    }

    override suspend fun existsIn(
        directory: MemoDirectoryType,
        filename: String,
    ): Boolean =
        when (directory) {
            MemoDirectoryType.MAIN -> exists(filename)
            MemoDirectoryType.TRASH -> trashExists(filename)
        }

    // --- File listing ---

    private suspend fun listFiles(targetFilename: String?): List<FileContent> =
        withContext(safIoDispatcher) {
            val root = getRoot() ?: return@withContext emptyList()
            root.listFiles().mapNotNull { file ->
                val name = file.name
                if (name != null &&
                    name.endsWith(".md") &&
                    (targetFilename == null || name == targetFilename)
                ) {
                    val content = readFileFromUri(file.uri)
                    if (content != null) {
                        FileContent(name, content, file.lastModified())
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }

    private suspend fun listTrashFiles(): List<FileContent> =
        withContext(safIoDispatcher) {
            val trashDir = getTrashDir() ?: return@withContext emptyList()
            trashDir.listFiles().mapNotNull { file ->
                val name = file.name
                if (name != null && name.endsWith(".md")) {
                    val content = readFileFromUri(file.uri)
                    if (content != null) {
                        FileContent(name, content, file.lastModified())
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }

    private suspend fun listMetadata(): List<FileMetadata> =
        withContext(safIoDispatcher) {
            try {
                // Try optimized query first
                val optimizedResults = queryChildDocuments()
                if (optimizedResults.isNotEmpty()) {
                    return@withContext optimizedResults
                }
                // Fallback to DocumentFile if optimized returns empty
                listMetadataSlow()
            } catch (e: Exception) {
                listMetadataSlow()
            }
        }

    private fun listMetadataSlow(): List<FileMetadata> {
        val root = getRoot() ?: return emptyList()
        return root.listFiles().mapNotNull { file ->
            val name = file.name
            if (name != null && name.endsWith(".md")) {
                FileMetadata(name, file.lastModified())
            } else {
                null
            }
        }
    }

    private fun queryChildDocuments(): List<FileMetadata> {
        val childUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                rootUri,
                DocumentsContract.getDocumentId(rootUri),
            )

        val result = mutableListOf<FileMetadata>()
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )

        context.contentResolver.query(childUri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val timeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val name = if (nameIndex != -1) cursor.getString(nameIndex) else null
                val lastModified = if (timeIndex != -1) cursor.getLong(timeIndex) else 0L

                if (name != null && name.endsWith(".md")) {
                    result.add(FileMetadata(name, lastModified))
                }
            }
        }
        return result
    }

    private suspend fun listTrashMetadata(): List<FileMetadata> =
        withContext(safIoDispatcher) {
            val trashDir = getTrashDir() ?: return@withContext emptyList()
            trashDir.listFiles().mapNotNull { file ->
                val name = file.name
                if (name != null && name.endsWith(".md")) {
                    FileMetadata(name, file.lastModified())
                } else {
                    null
                }
            }
        }

    private suspend fun listMetadataWithIds(): List<FileMetadataWithId> =
        withContext(safIoDispatcher) {
            try {
                val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
                val results = queryChildDocumentsWithIds(rootDocId)
                if (results.isNotEmpty()) {
                    results
                } else {
                    listMetadata().map { FileMetadataWithId(it.filename, it.lastModified, it.filename) }
                }
            } catch (e: Exception) {
                timber.log.Timber.e(e, "listMetadataWithIds failed, falling back")
                listMetadata().map {
                    FileMetadataWithId(
                        it.filename,
                        it.lastModified,
                        it.filename,
                    )
                }
            }
        }

    private fun queryChildDocumentsWithIds(parentDocId: String): List<FileMetadataWithId> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)

        val result = mutableListOf<FileMetadataWithId>()
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )

        context.contentResolver.query(childUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val timeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val docId = if (idIndex != -1) cursor.getString(idIndex) else null
                val name = if (nameIndex != -1) cursor.getString(nameIndex) else null
                val lastModified = if (timeIndex != -1) cursor.getLong(timeIndex) else 0L

                if (docId != null && name != null && name.endsWith(".md")) {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                    result.add(FileMetadataWithId(name, lastModified, docId, fileUri.toString()))
                }
            }
        }
        return result
    }

    private suspend fun listTrashMetadataWithIds(): List<FileMetadataWithId> =
        withContext(safIoDispatcher) {
            try {
                val trashDir = getTrashDir() ?: return@withContext emptyList()
                val trashDocId = DocumentsContract.getDocumentId(trashDir.uri)
                val results = queryChildDocumentsWithIds(trashDocId)
                if (results.isNotEmpty()) {
                    results
                } else {
                    listTrashMetadata().map { FileMetadataWithId(it.filename, it.lastModified, it.filename) }
                }
            } catch (e: Exception) {
                timber.log.Timber.e(e, "listTrashMetadataWithIds failed, falling back")
                listTrashMetadata().map {
                    FileMetadataWithId(
                        it.filename,
                        it.lastModified,
                        it.filename,
                    )
                }
            }
        }

    private suspend fun getFileMetadata(filename: String): FileMetadata? =
        withContext(safIoDispatcher) {
            val root = getRoot() ?: return@withContext null
            val file = root.findFile(filename)
            if (file != null) {
                FileMetadata(filename, file.lastModified())
            } else {
                null
            }
        }

    private suspend fun getTrashFileMetadata(filename: String): FileMetadata? =
        withContext(safIoDispatcher) {
            val trash = getTrashDir() ?: return@withContext null
            val file = trash.findFile(filename)
            if (file != null) {
                FileMetadata(filename, file.lastModified())
            } else {
                null
            }
        }

    // --- File reading ---

    private suspend fun readFile(filename: String): String? =
        withContext(safIoDispatcher) {
            val root = getRoot() ?: return@withContext null
            val file = root.findFile(filename) ?: return@withContext null
            readFileFromUri(file.uri)
        }

    override suspend fun readFile(uri: Uri): String? =
        withContext(safIoDispatcher) {
            readFileFromUri(uri)
        }

    private suspend fun readTrashFile(filename: String): String? =
        withContext(safIoDispatcher) {
            val trash = getTrashDir() ?: return@withContext null
            val file = trash.findFile(filename) ?: return@withContext null
            readFileFromUri(file.uri)
        }

    private suspend fun readFileByDocumentId(documentId: String): String? =
        withContext(safIoDispatcher) {
            try {
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
                readFileFromUri(fileUri)
            } catch (e: Exception) {
                readFile(documentId) // Fallback
            }
        }

    private suspend fun readTrashFileByDocumentId(documentId: String): String? =
        withContext(safIoDispatcher) {
            try {
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
                readFileFromUri(fileUri)
            } catch (e: Exception) {
                readTrashFile(documentId) // Fallback
            }
        }

    private suspend fun readHead(
        filename: String,
        maxChars: Int,
    ): String? =
        withContext(safIoDispatcher) {
            val root = getRoot() ?: return@withContext null
            val file = root.findFile(filename) ?: return@withContext null
            try {
                context.contentResolver.openInputStream(file.uri)?.buffered()?.use { bis ->
                    val buf = ByteArray(maxChars)
                    val n = bis.read(buf)
                    if (n <= 0) null else String(buf, 0, n)
                }
            } catch (e: Exception) {
                null
            }
        }

    private suspend fun readHeadByDocumentId(
        documentId: String,
        maxChars: Int,
    ): String? =
        withContext(safIoDispatcher) {
            try {
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
                context.contentResolver.openInputStream(fileUri)?.buffered()?.use { bis ->
                    val buf = ByteArray(maxChars)
                    val n = bis.read(buf)
                    if (n <= 0) null else String(buf, 0, n)
                }
            } catch (e: Exception) {
                // Fallback to filename path
                readHead(documentId, maxChars)
            }
        }

    // --- File writing ---

    private suspend fun saveFile(
        filename: String,
        content: String,
        append: Boolean,
        uri: Uri?,
    ): String? =
        withContext(safIoDispatcher) {
            withSecurityRetry(operation = "saveFile($filename)", fallbackValue = null) {
                if (uri != null) {
                    // O(1) optimized write
                    val mode = if (append) "wa" else "wt"
                    try {
                        context.contentResolver.openOutputStream(uri, mode)?.use {
                            it.write(content.toByteArray())
                        }
                        return@withSecurityRetry uri.toString()
                    } catch (e: Exception) {
                        // Fallback to findFile if URI is stale
                        timber.log.Timber.w(e, "Failed to write using cached URI, falling back to findFile")
                    }
                }

                val root = getRoot() ?: throw SecurityException("Cannot access SAF root for saveFile")
                var file = root.findFile(filename)
                if (file == null) {
                    file = root.createFile("text/markdown", filename)
                }
                file?.uri?.let { newUri ->
                    val mode = if (append) "wa" else "wt"
                    context.contentResolver.openOutputStream(newUri, mode)?.use {
                        it.write(content.toByteArray())
                    }
                    newUri.toString()
                }
            }
        }

    private suspend fun saveTrashFile(
        filename: String,
        content: String,
        append: Boolean,
    ) = withContext(safIoDispatcher) {
        withSecurityRetry(operation = "saveTrashFile($filename)", fallbackValue = Unit) {
            val trash = getOrCreateTrashDir() ?: throw SecurityException("Cannot access SAF trash for saveTrashFile")
            var file = trash.findFile(filename)
            if (file == null) {
                file = trash.createFile("text/markdown", filename)
            }
            file?.uri?.let { uri ->
                val mode = if (append) "wa" else "wt"
                context.contentResolver.openOutputStream(uri, mode)?.use {
                    it.write(content.toByteArray())
                }
            }
            Unit
        }
    }

    // --- File deletion ---

    private suspend fun deleteFile(
        filename: String,
        uri: Uri?,
    ) = withContext(safIoDispatcher) {
        withSecurityRetry(operation = "deleteFile($filename)", fallbackValue = Unit) {
            if (uri != null) {
                try {
                    // O(1) optimized delete
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                    return@withSecurityRetry Unit
                } catch (e: Exception) {
                    // Fallback
                    timber.log.Timber.w(e, "Failed to delete using cached URI, falling back to findFile")
                }
            }
            val root = getRoot() ?: throw SecurityException("Cannot access SAF root for deleteFile")
            root.findFile(filename)?.delete()
            Unit
        }
    }

    private suspend fun deleteTrashFile(filename: String) =
        withContext(safIoDispatcher) {
            val trash = getTrashDir()
            trash?.findFile(filename)?.delete()
            Unit
        }

    // --- File existence ---

    private suspend fun exists(filename: String): Boolean =
        withContext(safIoDispatcher) {
            val root = getRoot()
            root?.findFile(filename)?.exists() == true
        }

    private suspend fun trashExists(filename: String): Boolean =
        withContext(safIoDispatcher) {
            val trash = getTrashDir()
            trash?.findFile(filename)?.exists() == true
        }

    // --- Image operations (ImageStorageBackend) ---

    override suspend fun saveImage(
        sourceUri: Uri,
        filename: String,
    ) {
        withContext(safIoDispatcher) {
            val root = getRoot() ?: throw java.io.IOException("Cannot access image directory")

            val inputStream =
                context.contentResolver.openInputStream(sourceUri)
                    ?: throw java.io.IOException("Cannot open source image URI")

            val extension = filename.substringAfterLast(".", "jpg")
            val newFile =
                root.createFile("image/$extension", filename)
                    ?: throw java.io.IOException("Cannot create image file")

            context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                inputStream.use { input -> input.copyTo(outputStream) }
            } ?: throw java.io.IOException("Cannot write to image file")
        }
    }

    override suspend fun listImageFiles(): List<Pair<String, String>> =
        withContext(safIoDispatcher) {
            val root = getRoot() ?: return@withContext emptyList()
            root.listFiles().mapNotNull { file ->
                val name = file.name
                if (name == null || !file.isFile) return@mapNotNull null
                val mimeType = file.type
                if (mimeType?.startsWith("image/") == true || isImageFilename(name)) {
                    name to file.uri.toString()
                } else {
                    null
                }
            }
        }

    override suspend fun deleteImage(filename: String) =
        withContext(safIoDispatcher) {
            try {
                val root = getRoot()
                root?.findFile(filename)?.delete()
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to delete image: $filename")
            }
            Unit
        }

    // --- Voice operations (VoiceStorageBackend) ---

    override suspend fun createVoiceFile(filename: String): Uri =
        withContext(safIoDispatcher) {
            val root = getRoot() ?: throw java.io.IOException("Cannot access voice directory")

            // Guess mime type from filename
            val extension = filename.substringAfterLast('.', "m4a")
            val mimeType =
                when (extension) {
                    "m4a" -> "audio/mp4"
                    "mp3" -> "audio/mpeg"
                    "aac" -> "audio/aac"
                    else -> "audio/mp4"
                }

            val file =
                root.createFile(mimeType, filename)
                    ?: throw java.io.IOException("Cannot create voice file")
            file.uri
        }

    override suspend fun deleteVoiceFile(filename: String) =
        withContext(safIoDispatcher) {
            try {
                val root = getRoot()
                root?.findFile(filename)?.delete()
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to delete voice file: $filename")
            }
            Unit
        }

    override suspend fun createDirectory(name: String): String =
        withContext(safIoDispatcher) {
            val root = getRoot() ?: throw java.io.IOException("Cannot access root directory")
            val dir =
                root.findFile(name) ?: root.createDirectory(name)
                    ?: throw java.io.IOException("Cannot create directory $name")
            dir.uri.toString()
        }

    private companion object {
        private const val SECURITY_RETRY_ATTEMPTS = 2
    }
}
