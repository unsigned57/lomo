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

    private val trashDir: File get() = File(rootDir, ".trash")

    private fun isImageFilename(name: String): Boolean {
        val extension = name.substringAfterLast('.', "")
        return extension.isNotBlank() && extension.lowercase() in imageExtensions
    }

    private fun ensureRootExists() {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    private fun ensureTrashExists() {
        if (!trashDir.exists()) trashDir.mkdirs()
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
            MemoDirectoryType.TRASH -> readTrashFile(filename)?.take(maxChars)
        }

    override suspend fun readHeadByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
        maxChars: Int,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> readHeadByDocumentId(documentId, maxChars)
            MemoDirectoryType.TRASH -> readTrashFileByDocumentId(documentId)?.take(maxChars)
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
            listMetadata().map {
                FileMetadataWithId(
                    it.filename,
                    it.lastModified,
                    it.filename,
                    File(rootDir, it.filename).absolutePath,
                )
            }
        }

    override suspend fun listTrashMetadataWithIds(): List<FileMetadataWithId> =
        withContext(Dispatchers.IO) {
            listTrashMetadata().map {
                FileMetadataWithId(
                    it.filename,
                    it.lastModified,
                    it.filename,
                    File(trashDir, it.filename).absolutePath,
                )
            }
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

    override suspend fun getTrashFileMetadata(filename: String): FileMetadata? =
        withContext(Dispatchers.IO) {
            val file = File(trashDir, filename)
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

    override suspend fun readFile(uri: Uri): String? =
        withContext(Dispatchers.IO) {
            // Check if it's a file URI
            if (uri.scheme == "file") {
                val path = uri.path ?: return@withContext null
                val file = File(path)
                if (file.exists()) file.readText() else null
            } else {
                null
            }
        }

    override suspend fun readTrashFile(filename: String): String? =
        withContext(Dispatchers.IO) {
            val file = File(trashDir, filename)
            if (file.exists()) file.readText() else null
        }

    override suspend fun readFileByDocumentId(documentId: String): String? =
        readFile(documentId) // documentId == filename for direct access

    override suspend fun readTrashFileByDocumentId(documentId: String): String? = readTrashFile(documentId)

    override suspend fun readHead(
        filename: String,
        maxChars: Int,
    ): String? =
        withContext(Dispatchers.IO) {
            val file = File(rootDir, filename)
            if (!file.exists()) return@withContext null
            file.inputStream().buffered().use { bis ->
                val buf = ByteArray(maxChars)
                val n = bis.read(buf)
                if (n <= 0) null else String(buf, 0, n)
            }
        }

    override suspend fun readHeadByDocumentId(
        documentId: String,
        maxChars: Int,
    ): String? = readHead(documentId, maxChars)

    // --- File writing ---

    override suspend fun saveFile(
        filename: String,
        content: String,
        append: Boolean,
        uri: Uri?,
    ): String? =
        withContext(Dispatchers.IO) {
            ensureRootExists()
            val file = File(rootDir, filename)
            if (append) file.appendText(content) else file.writeText(content)
            file.absolutePath
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

    override suspend fun deleteFile(
        filename: String,
        uri: Uri?,
    ) = withContext(Dispatchers.IO) {
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

            rootDir
                .listFiles()
                ?.asSequence()
                ?.filter { file -> file.isFile && isImageFilename(file.name) }
                ?.map { file -> file.name to Uri.fromFile(file).toString() }
                ?.toList()
                ?: emptyList()
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
