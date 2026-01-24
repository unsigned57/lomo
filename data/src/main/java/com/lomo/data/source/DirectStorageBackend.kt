package com.lomo.data.source

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Storage backend implementation using direct file system access.
 * Handles traditional file paths.
 */
class DirectStorageBackend(
    private val rootDir: File,
) : StorageBackend,
    ImageStorageBackend,
    VoiceStorageBackend {
    private val trashDir: File get() = File(rootDir, ".trash")

    private fun ensureRootExists() {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    private fun ensureTrashExists() {
        if (!trashDir.exists()) trashDir.mkdirs()
    }

    // --- File listing ---

    override suspend fun listFiles(targetFilename: String?): List<FileContent> =
        withContext(Dispatchers.IO) {
            if (!rootDir.exists() || !rootDir.isDirectory) return@withContext emptyList()

            rootDir
                .listFiles { _, name ->
                    name.endsWith(".md") && (targetFilename == null || name == targetFilename)
                }?.map { file ->
                    FileContent(file.name, file.readText(), file.lastModified())
                } ?: emptyList()
        }

    override suspend fun listTrashFiles(): List<FileContent> =
        withContext(Dispatchers.IO) {
            if (!trashDir.exists() || !trashDir.isDirectory) return@withContext emptyList()

            trashDir.listFiles { _, name -> name.endsWith(".md") }?.map { file ->
                FileContent(file.name, file.readText(), file.lastModified())
            } ?: emptyList()
        }

    override suspend fun listMetadata(): List<FileMetadata> =
        withContext(Dispatchers.IO) {
            if (!rootDir.exists() || !rootDir.isDirectory) return@withContext emptyList()

            rootDir.listFiles { _, name -> name.endsWith(".md") }?.map { file ->
                FileMetadata(file.name, file.lastModified())
            } ?: emptyList()
        }

    override suspend fun listTrashMetadata(): List<FileMetadata> =
        withContext(Dispatchers.IO) {
            if (!trashDir.exists() || !trashDir.isDirectory) return@withContext emptyList()

            trashDir.listFiles { _, name -> name.endsWith(".md") }?.map { file ->
                FileMetadata(file.name, file.lastModified())
            } ?: emptyList()
        }

    override suspend fun listMetadataWithIds(): List<FileMetadataWithId> =
        withContext(Dispatchers.IO) {
            // For direct file access, use filename as pseudo document ID
            listMetadata().map { FileMetadataWithId(it.filename, it.lastModified, it.filename) }
        }

    override suspend fun listTrashMetadataWithIds(): List<FileMetadataWithId> =
        withContext(Dispatchers.IO) {
            listTrashMetadata().map { FileMetadataWithId(it.filename, it.lastModified, it.filename) }
        }

    override suspend fun getFileMetadata(filename: String): FileMetadata? =
        withContext(Dispatchers.IO) {
            val file = File(rootDir, filename)
            if (file.exists()) {
                FileMetadata(filename, file.lastModified())
            } else {
                null
            }
        }

    // --- File reading ---

    override suspend fun readFile(filename: String): String? =
        withContext(Dispatchers.IO) {
            val file = File(rootDir, filename)
            if (file.exists()) file.readText() else null
        }

    override suspend fun readTrashFile(filename: String): String? =
        withContext(Dispatchers.IO) {
            val file = File(trashDir, filename)
            if (file.exists()) file.readText() else null
        }

    override suspend fun readFileByDocumentId(documentId: String): String? =
        readFile(documentId) // documentId == filename for direct access

    override suspend fun readTrashFileByDocumentId(documentId: String): String? = readTrashFile(documentId)

    // --- File writing ---

    override suspend fun saveFile(
        filename: String,
        content: String,
        append: Boolean,
    ) = withContext(Dispatchers.IO) {
        ensureRootExists()
        val file = File(rootDir, filename)
        if (append) file.appendText(content) else file.writeText(content)
    }

    override suspend fun saveTrashFile(
        filename: String,
        content: String,
        append: Boolean,
    ) = withContext(Dispatchers.IO) {
        ensureTrashExists()
        val file = File(trashDir, filename)
        if (append) file.appendText(content) else file.writeText(content)
    }

    // --- File deletion ---

    override suspend fun deleteFile(filename: String) =
        withContext(Dispatchers.IO) {
            File(rootDir, filename).delete()
            Unit
        }

    override suspend fun deleteTrashFile(filename: String) =
        withContext(Dispatchers.IO) {
            File(trashDir, filename).delete()
            Unit
        }

    // --- File existence ---

    override suspend fun exists(filename: String): Boolean =
        withContext(Dispatchers.IO) {
            File(rootDir, filename).exists()
        }

    override suspend fun trashExists(filename: String): Boolean =
        withContext(Dispatchers.IO) {
            File(trashDir, filename).exists()
        }

    // --- Image operations (ImageStorageBackend) ---

    override suspend fun saveImage(
        sourceUri: Uri,
        filename: String,
    ) {
        withContext(Dispatchers.IO) {
            ensureRootExists()
            // For direct file backend, we need a Context to resolve the URI
            // This is typically handled at a higher level - see FileDataSourceImpl
            throw UnsupportedOperationException(
                "DirectStorageBackend requires Context to resolve source URIs. Use FileDataSourceImpl.saveImage() instead.",
            )
        }
    }

    override suspend fun listImageFiles(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            if (!rootDir.exists() || !rootDir.isDirectory) return@withContext emptyList()

            rootDir.listFiles()?.map { file ->
                file.name to Uri.fromFile(file).toString()
            } ?: emptyList()
        }

    override suspend fun deleteImage(filename: String) =
        withContext(Dispatchers.IO) {
            try {
                val file = File(rootDir, filename)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to delete image: $filename")
            }
            Unit
        }

    // --- Voice operations (VoiceStorageBackend) ---

    override suspend fun createVoiceFile(filename: String): Uri =
        withContext(Dispatchers.IO) {
            ensureRootExists()
            val file = File(rootDir, filename)
            // Return file:// URI
            Uri.fromFile(file)
        }

    override suspend fun deleteVoiceFile(filename: String) =
        withContext(Dispatchers.IO) {
            try {
                val file = File(rootDir, filename)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to delete voice file: $filename")
            }
            Unit
        }

    override suspend fun createDirectory(name: String): String =
        withContext(Dispatchers.IO) {
            ensureRootExists()
            val dir = File(rootDir, name)
            if (!dir.exists()) {
                if (!dir.mkdirs()) throw java.io.IOException("Cannot create directory $name")
            }
            dir.absolutePath
        }
}
