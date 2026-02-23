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
) : StorageBackend,
    ImageStorageBackend,
    VoiceStorageBackend {
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
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        return if (subDir != null) {
            root.findFile(subDir) ?: root.createDirectory(subDir)
        } else {
            root
        }
    }

    private fun isImageFilename(name: String): Boolean {
        val extension = name.substringAfterLast('.', "")
        return extension.isNotBlank() && extension.lowercase() in imageExtensions
    }

    // Cache trash dir to avoid repeated findFile calls
    private var cachedTrashDir: DocumentFile? = null

    private fun getTrashDir(): DocumentFile? {
        if (cachedTrashDir != null && cachedTrashDir!!.exists()) {
            return cachedTrashDir
        }
        cachedTrashDir = getRoot()?.findFile(".trash")
        return cachedTrashDir
    }

    private fun getOrCreateTrashDir(): DocumentFile? {
        if (cachedTrashDir != null && cachedTrashDir!!.exists()) {
            return cachedTrashDir
        }
        val root = getRoot() ?: return null
        cachedTrashDir = root.findFile(".trash") ?: root.createDirectory(".trash")
        return cachedTrashDir
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

    override suspend fun listFiles(targetFilename: String?): List<FileContent> =
        withContext(Dispatchers.IO) {
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

    override suspend fun listTrashFiles(): List<FileContent> =
        withContext(Dispatchers.IO) {
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

    override suspend fun listMetadata(): List<FileMetadata> =
        withContext(Dispatchers.IO) {
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

    override suspend fun listTrashMetadata(): List<FileMetadata> =
        withContext(Dispatchers.IO) {
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

    override suspend fun listMetadataWithIds(): List<FileMetadataWithId> =
        withContext(Dispatchers.IO) {
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

    override suspend fun listTrashMetadataWithIds(): List<FileMetadataWithId> =
        withContext(Dispatchers.IO) {
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

    override suspend fun getFileMetadata(filename: String): FileMetadata? =
        withContext(Dispatchers.IO) {
            val root = getRoot() ?: return@withContext null
            val file = root.findFile(filename)
            if (file != null) {
                FileMetadata(filename, file.lastModified())
            } else {
                null
            }
        }

    override suspend fun getTrashFileMetadata(filename: String): FileMetadata? =
        withContext(Dispatchers.IO) {
            val trash = getTrashDir() ?: return@withContext null
            val file = trash.findFile(filename)
            if (file != null) {
                FileMetadata(filename, file.lastModified())
            } else {
                null
            }
        }

    // --- File reading ---

    override suspend fun readFile(filename: String): String? =
        withContext(Dispatchers.IO) {
            val root = getRoot() ?: return@withContext null
            val file = root.findFile(filename) ?: return@withContext null
            readFileFromUri(file.uri)
        }

    override suspend fun readFile(uri: Uri): String? =
        withContext(Dispatchers.IO) {
            readFileFromUri(uri)
        }

    override suspend fun readTrashFile(filename: String): String? =
        withContext(Dispatchers.IO) {
            val trash = getTrashDir() ?: return@withContext null
            val file = trash.findFile(filename) ?: return@withContext null
            readFileFromUri(file.uri)
        }

    override suspend fun readFileByDocumentId(documentId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
                readFileFromUri(fileUri)
            } catch (e: Exception) {
                readFile(documentId) // Fallback
            }
        }

    override suspend fun readTrashFileByDocumentId(documentId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
                readFileFromUri(fileUri)
            } catch (e: Exception) {
                readTrashFile(documentId) // Fallback
            }
        }

    override suspend fun readHead(
        filename: String,
        maxChars: Int,
    ): String? =
        withContext(Dispatchers.IO) {
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

    override suspend fun readHeadByDocumentId(
        documentId: String,
        maxChars: Int,
    ): String? =
        withContext(Dispatchers.IO) {
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

    override suspend fun saveFile(
        filename: String,
        content: String,
        append: Boolean,
        uri: Uri?,
    ): String? =
        withContext(Dispatchers.IO) {
            if (uri != null) {
                // O(1) optimized write
                val mode = if (append) "wa" else "wt"
                try {
                    context.contentResolver.openOutputStream(uri, mode)?.use {
                        it.write(content.toByteArray())
                    }
                    return@withContext uri.toString()
                } catch (e: Exception) {
                    // Fallback to findFile if URI is stale
                    timber.log.Timber.w(e, "Failed to write using cached URI, falling back to findFile")
                }
            }

            val root = getRoot() ?: return@withContext null
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

    override suspend fun saveTrashFile(
        filename: String,
        content: String,
        append: Boolean,
    ) = withContext(Dispatchers.IO) {
        val trash = getOrCreateTrashDir() ?: return@withContext
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

    // --- File deletion ---

    override suspend fun deleteFile(
        filename: String,
        uri: Uri?,
    ) = withContext(Dispatchers.IO) {
        if (uri != null) {
            try {
                // O(1) optimized delete
                DocumentsContract.deleteDocument(context.contentResolver, uri)
                return@withContext
            } catch (e: Exception) {
                // Fallback
                timber.log.Timber.w(e, "Failed to delete using cached URI, falling back to findFile")
            }
        }
        val root = getRoot()
        root?.findFile(filename)?.delete()
        Unit
    }

    override suspend fun deleteTrashFile(filename: String) =
        withContext(Dispatchers.IO) {
            val trash = getTrashDir()
            trash?.findFile(filename)?.delete()
            Unit
        }

    // --- File existence ---

    override suspend fun exists(filename: String): Boolean =
        withContext(Dispatchers.IO) {
            val root = getRoot()
            root?.findFile(filename)?.exists() == true
        }

    override suspend fun trashExists(filename: String): Boolean =
        withContext(Dispatchers.IO) {
            val trash = getTrashDir()
            trash?.findFile(filename)?.exists() == true
        }

    // --- Image operations (ImageStorageBackend) ---

    override suspend fun saveImage(
        sourceUri: Uri,
        filename: String,
    ) {
        withContext(Dispatchers.IO) {
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
        withContext(Dispatchers.IO) {
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
        withContext(Dispatchers.IO) {
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
        withContext(Dispatchers.IO) {
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
        withContext(Dispatchers.IO) {
            try {
                val root = getRoot()
                root?.findFile(filename)?.delete()
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to delete voice file: $filename")
            }
            Unit
        }

    override suspend fun createDirectory(name: String): String =
        withContext(Dispatchers.IO) {
            val root = getRoot() ?: throw java.io.IOException("Cannot access root directory")
            val dir =
                root.findFile(name) ?: root.createDirectory(name)
                    ?: throw java.io.IOException("Cannot create directory $name")
            dir.uri.toString()
        }
}
