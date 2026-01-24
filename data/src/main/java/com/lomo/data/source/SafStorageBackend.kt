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
    private fun getRoot(): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        return if (subDir != null) {
            root.findFile(subDir) ?: root.createDirectory(subDir)
        } else {
            root
        }
    }

    private fun getTrashDir(): DocumentFile? = getRoot()?.findFile(".trash")

    private fun getOrCreateTrashDir(): DocumentFile? {
        val root = getRoot() ?: return null
        return root.findFile(".trash") ?: root.createDirectory(".trash")
    }

    private fun readFileFromUri(uri: Uri): String? =
        try {
            context.contentResolver.openInputStream(uri)?.use {
                BufferedReader(InputStreamReader(it)).readText()
            }
        } catch (e: Exception) {
            null
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
                listMetadata().map { FileMetadataWithId(it.filename, it.lastModified, it.filename) }
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
                    result.add(FileMetadataWithId(name, lastModified, docId))
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
                listTrashMetadata().map { FileMetadataWithId(it.filename, it.lastModified, it.filename) }
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

    // --- File reading ---

    override suspend fun readFile(filename: String): String? =
        withContext(Dispatchers.IO) {
            val root = getRoot() ?: return@withContext null
            val file = root.findFile(filename) ?: return@withContext null
            readFileFromUri(file.uri)
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

    // --- File writing ---

    override suspend fun saveFile(
        filename: String,
        content: String,
        append: Boolean,
    ) = withContext(Dispatchers.IO) {
        val root = getRoot() ?: return@withContext
        var file = root.findFile(filename)
        if (file == null) {
            file = root.createFile("text/markdown", filename)
        }
        file?.uri?.let { uri ->
            val mode = if (append) "wa" else "wt"
            context.contentResolver.openOutputStream(uri, mode)?.use {
                it.write(content.toByteArray())
            }
        }
        Unit
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

    override suspend fun deleteFile(filename: String) =
        withContext(Dispatchers.IO) {
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
                file.name?.let { name -> name to file.uri.toString() }
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
