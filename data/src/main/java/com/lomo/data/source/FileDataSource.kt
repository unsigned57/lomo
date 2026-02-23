package com.lomo.data.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

interface FileDataSource {
    suspend fun setRoot(pathOrUri: String)

    fun getRootFlow(): Flow<String?>

    fun getRootDisplayNameFlow(): Flow<String?>

    fun getImageRootFlow(): Flow<String?>

    fun getImageRootDisplayNameFlow(): Flow<String?>

    suspend fun setImageRoot(pathOrUri: String)

    fun getVoiceRootFlow(): Flow<String?>

    fun getVoiceRootDisplayNameFlow(): Flow<String?>

    suspend fun setVoiceRoot(pathOrUri: String)

    // Context-aware API

    suspend fun listFilesIn(
        directory: MemoDirectoryType,
        targetFilename: String? = null,
    ): List<FileContent>

    suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata>

    suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId>

    suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String?

    suspend fun readHeadIn(
        directory: MemoDirectoryType,
        filename: String,
        maxChars: Int = 256,
    ): String?

    suspend fun readHeadByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
        maxChars: Int = 256,
    ): String?

    suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String?

    suspend fun readFile(uri: Uri): String?

    suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean = false,
        uri: Uri? = null,
    ): String?

    suspend fun deleteFileIn(
        directory: MemoDirectoryType,
        filename: String,
        uri: Uri? = null,
    )

    suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): FileMetadata?

    suspend fun existsIn(
        directory: MemoDirectoryType,
        filename: String,
    ): Boolean

    // Backward-compatible wrappers

    suspend fun listFiles(targetFilename: String? = null): List<FileContent> = listFilesIn(MemoDirectoryType.MAIN, targetFilename)

    suspend fun listTrashFiles(): List<FileContent> = listFilesIn(MemoDirectoryType.TRASH)

    suspend fun listMetadata(): List<FileMetadata> = listMetadataIn(MemoDirectoryType.MAIN)

    suspend fun listTrashMetadata(): List<FileMetadata> = listMetadataIn(MemoDirectoryType.TRASH)

    suspend fun listMetadataWithIds(): List<FileMetadataWithId> = listMetadataWithIdsIn(MemoDirectoryType.MAIN)

    suspend fun listTrashMetadataWithIds(): List<FileMetadataWithId> = listMetadataWithIdsIn(MemoDirectoryType.TRASH)

    suspend fun readFileByDocumentId(documentId: String): String? = readFileByDocumentIdIn(MemoDirectoryType.MAIN, documentId)

    suspend fun readTrashFileByDocumentId(documentId: String): String? = readFileByDocumentIdIn(MemoDirectoryType.TRASH, documentId)

    suspend fun readHead(
        filename: String,
        maxChars: Int = 256,
    ): String? = readHeadIn(MemoDirectoryType.MAIN, filename, maxChars)

    suspend fun readHeadByDocumentId(
        documentId: String,
        maxChars: Int = 256,
    ): String? = readHeadByDocumentIdIn(MemoDirectoryType.MAIN, documentId, maxChars)

    suspend fun readFile(filename: String): String? = readFileIn(MemoDirectoryType.MAIN, filename)

    suspend fun readTrashFile(filename: String): String? = readFileIn(MemoDirectoryType.TRASH, filename)

    suspend fun saveFile(
        filename: String,
        content: String,
        append: Boolean = false,
        uri: Uri? = null,
    ): String? = saveFileIn(MemoDirectoryType.MAIN, filename, content, append, uri)

    suspend fun saveTrashFile(
        filename: String,
        content: String,
        append: Boolean = true,
    ) {
        saveFileIn(MemoDirectoryType.TRASH, filename, content, append, uri = null)
    }

    suspend fun deleteFile(
        filename: String,
        uri: Uri? = null,
    ) {
        deleteFileIn(MemoDirectoryType.MAIN, filename, uri)
    }

    suspend fun deleteTrashFile(filename: String) {
        deleteFileIn(MemoDirectoryType.TRASH, filename, uri = null)
    }

    suspend fun getFileMetadata(filename: String): FileMetadata? = getFileMetadataIn(MemoDirectoryType.MAIN, filename)

    suspend fun getTrashFileMetadata(filename: String): FileMetadata? = getFileMetadataIn(MemoDirectoryType.TRASH, filename)

    suspend fun exists(filename: String): Boolean = existsIn(MemoDirectoryType.MAIN, filename)

    suspend fun trashExists(filename: String): Boolean = existsIn(MemoDirectoryType.TRASH, filename)

    suspend fun saveImage(uri: Uri): String

    suspend fun listImageFiles(): List<Pair<String, String>>

    suspend fun deleteImage(filename: String)

    suspend fun createVoiceFile(filename: String): Uri

    suspend fun deleteVoiceFile(filename: String)

    suspend fun createDirectory(name: String): String
}

data class FileContent(
    val filename: String,
    val content: String,
    val lastModified: Long,
)

data class FileMetadata(
    val filename: String,
    val lastModified: Long,
)

data class FileMetadataWithId(
    val filename: String,
    val lastModified: Long,
    val documentId: String,
    val uriString: String? = null,
)

/**
 * FileDataSource implementation using Strategy Pattern.
 *
 * Delegates file operations to appropriate StorageBackend:
 * - SafStorageBackend for content:// URIs (SAF)
 * - DirectStorageBackend for file:// paths
 *
 * This refactoring reduces code duplication and improves testability.
 */
class FileDataSourceImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataStore: com.lomo.data.local.datastore.LomoDataStore,
    ) : FileDataSource {
        // --- Configuration methods ---

        override suspend fun setRoot(pathOrUri: String) {
            if (pathOrUri.startsWith("content://")) {
                dataStore.updateRootUri(pathOrUri)
                dataStore.updateRootDirectory(null)
            } else {
                dataStore.updateRootDirectory(pathOrUri)
                dataStore.updateRootUri(null)
            }
        }

        override fun getRootFlow(): Flow<String?> = combine(dataStore.rootUri, dataStore.rootDirectory) { uri, path -> uri ?: path }

        override fun getRootDisplayNameFlow(): Flow<String?> =
            getRootFlow().map { uriOrPath ->
                when {
                    uriOrPath == null -> null
                    uriOrPath.startsWith("content://") -> getDisplayName(Uri.parse(uriOrPath))
                    else -> uriOrPath
                }
            }

        override fun getImageRootFlow(): Flow<String?> = combine(dataStore.imageUri, dataStore.imageDirectory) { uri, path -> uri ?: path }

        override fun getImageRootDisplayNameFlow(): Flow<String?> =
            getImageRootFlow().map { uriOrPath ->
                when {
                    uriOrPath == null -> null
                    uriOrPath.startsWith("content://") -> getDisplayName(Uri.parse(uriOrPath))
                    else -> uriOrPath
                }
            }

        override suspend fun setImageRoot(pathOrUri: String) {
            if (pathOrUri.startsWith("content://")) {
                dataStore.updateImageUri(pathOrUri)
                dataStore.updateImageDirectory(null)
            } else {
                dataStore.updateImageDirectory(pathOrUri)
                dataStore.updateImageUri(null)
            }
        }

        override fun getVoiceRootFlow(): Flow<String?> = combine(dataStore.voiceUri, dataStore.voiceDirectory) { uri, path -> uri ?: path }

        override fun getVoiceRootDisplayNameFlow(): Flow<String?> =
            getVoiceRootFlow().map { uriOrPath ->
                when {
                    uriOrPath == null -> null
                    uriOrPath.startsWith("content://") -> getDisplayName(Uri.parse(uriOrPath))
                    else -> uriOrPath
                }
            }

        override suspend fun setVoiceRoot(pathOrUri: String) {
            if (pathOrUri.startsWith("content://")) {
                dataStore.updateVoiceUri(pathOrUri)
                dataStore.updateVoiceDirectory(null)
            } else {
                dataStore.updateVoiceDirectory(pathOrUri)
                dataStore.updateVoiceUri(null)
            }
        }

        private fun getDisplayName(uri: Uri): String =
            try {
                DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: uri.toString()
            } catch (e: Exception) {
                uri.lastPathSegment ?: uri.toString()
            }

        // --- Backend resolution ---

        private var currentBackend: StorageBackend? = null
        private var currentRootConfig: String? = null

        private suspend fun getBackend(): StorageBackend? {
            val rootUri = dataStore.rootUri.first()
            val rootDir = dataStore.rootDirectory.first()

            val configKey = rootUri ?: rootDir

            // Return cached backend if configuration hasn't changed
            if (currentBackend != null && currentRootConfig == configKey) {
                return currentBackend
            }

            val backend =
                when {
                    rootUri != null -> SafStorageBackend(context, Uri.parse(rootUri))
                    rootDir != null -> DirectStorageBackend(java.io.File(rootDir))
                    else -> null
                }

            currentBackend = backend
            currentRootConfig = configKey
            return backend
        }

        private suspend fun getImageBackend(): Pair<StorageBackend?, Uri?> {
            val imageUri = dataStore.imageUri.first()
            val imageDir = dataStore.imageDirectory.first()

            return when {
                imageUri != null -> SafStorageBackend(context, Uri.parse(imageUri)) to Uri.parse(imageUri)
                imageDir != null -> DirectStorageBackend(java.io.File(imageDir)) to null
                else -> null to null
            }
        }

        // --- Delegated file operations ---

        override suspend fun listFilesIn(
            directory: MemoDirectoryType,
            targetFilename: String?,
        ): List<FileContent> = getBackend()?.listFilesIn(directory, targetFilename) ?: emptyList()

        override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> =
            getBackend()?.listMetadataIn(directory) ?: emptyList()

        override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> =
            getBackend()?.listMetadataWithIdsIn(directory) ?: emptyList()

        override suspend fun readFile(uri: Uri): String? = getBackend()?.readFile(uri)

        override suspend fun readFileByDocumentIdIn(
            directory: MemoDirectoryType,
            documentId: String,
        ): String? = getBackend()?.readFileByDocumentIdIn(directory, documentId)

        override suspend fun readHeadIn(
            directory: MemoDirectoryType,
            filename: String,
            maxChars: Int,
        ): String? = getBackend()?.readHeadIn(directory, filename, maxChars)

        override suspend fun readHeadByDocumentIdIn(
            directory: MemoDirectoryType,
            documentId: String,
            maxChars: Int,
        ): String? = getBackend()?.readHeadByDocumentIdIn(directory, documentId, maxChars)

        override suspend fun readFileIn(
            directory: MemoDirectoryType,
            filename: String,
        ): String? = getBackend()?.readFileIn(directory, filename)

        override suspend fun saveFileIn(
            directory: MemoDirectoryType,
            filename: String,
            content: String,
            append: Boolean,
            uri: Uri?,
        ): String? = getBackend()?.saveFileIn(directory, filename, content, append, uri)

        override suspend fun deleteFileIn(
            directory: MemoDirectoryType,
            filename: String,
            uri: Uri?,
        ) {
            getBackend()?.deleteFileIn(directory, filename, uri)
        }

        override suspend fun getFileMetadataIn(
            directory: MemoDirectoryType,
            filename: String,
        ): FileMetadata? = getBackend()?.getFileMetadataIn(directory, filename)

        override suspend fun existsIn(
            directory: MemoDirectoryType,
            filename: String,
        ): Boolean = getBackend()?.existsIn(directory, filename) ?: false

        // --- Image operations (require special handling for source URI resolution) ---

        override suspend fun saveImage(uri: Uri): String =
            withContext(Dispatchers.IO) {
                val (backend, imageUri) = getImageBackend()

                // Generate unique filename
                val timestamp = System.currentTimeMillis()
                val extension =
                    context.contentResolver.getType(uri)?.let { mimeType ->
                        when {
                            mimeType.contains("png") -> "png"
                            mimeType.contains("gif") -> "gif"
                            mimeType.contains("webp") -> "webp"
                            else -> "jpg"
                        }
                    } ?: "jpg"
                val filename = "img_$timestamp.$extension"

                when (backend) {
                    is SafStorageBackend -> {
                        backend.saveImage(uri, filename)
                    }

                    is DirectStorageBackend -> {
                        // DirectStorageBackend needs context to resolve URI
                        val imageDirectory =
                            dataStore.imageDirectory.first()
                                ?: throw java.io.IOException("No image directory configured")

                        val inputStream =
                            context.contentResolver.openInputStream(uri)
                                ?: throw java.io.IOException("Cannot open source image URI")

                        val targetDir = java.io.File(imageDirectory)
                        if (!targetDir.exists()) targetDir.mkdirs()

                        val targetFile = java.io.File(targetDir, filename)
                        targetFile.outputStream().use { outputStream ->
                            inputStream.use { input -> input.copyTo(outputStream) }
                        }
                    }

                    null -> {
                        throw java.io.IOException("No image directory configured")
                    }
                }

                filename
            }

        override suspend fun listImageFiles(): List<Pair<String, String>> {
            val (backend, _) = getImageBackend()
            return when (backend) {
                is SafStorageBackend -> backend.listImageFiles()
                is DirectStorageBackend -> backend.listImageFiles()
                else -> emptyList()
            }
        }

        override suspend fun deleteImage(filename: String) {
            val (backend, _) = getImageBackend()
            when (backend) {
                is SafStorageBackend -> {
                    backend.deleteImage(filename)
                }

                is DirectStorageBackend -> {
                    backend.deleteImage(filename)
                }

                else -> { /* No-op */ }
            }
        }

        // --- Voice operations ---

        private suspend fun getVoiceBackend(): VoiceStorageBackend? {
            val voiceUri = dataStore.voiceUri.first()
            val voiceDir = dataStore.voiceDirectory.first()

            // Use custom voice directory if set
            if (voiceUri != null) return SafStorageBackend(context, Uri.parse(voiceUri))
            if (voiceDir != null) return DirectStorageBackend(java.io.File(voiceDir))

            // Fallback to root directory (no subfolder)
            val rootUri = dataStore.rootUri.first()
            val rootDir = dataStore.rootDirectory.first()

            return when {
                rootUri != null -> SafStorageBackend(context, Uri.parse(rootUri))
                rootDir != null -> DirectStorageBackend(java.io.File(rootDir))
                else -> null
            }
        }

        override suspend fun createVoiceFile(filename: String): Uri {
            val backend = getVoiceBackend() ?: throw java.io.IOException("No storage configured")
            return backend.createVoiceFile(filename)
        }

        override suspend fun deleteVoiceFile(filename: String) {
            getVoiceBackend()?.deleteVoiceFile(filename)
        }

        override suspend fun createDirectory(name: String): String =
            getBackend()?.createDirectory(name)
                ?: throw java.io.IOException("No storage configured")
    }
