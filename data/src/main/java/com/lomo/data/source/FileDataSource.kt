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
import java.io.BufferedReader
import java.io.InputStreamReader
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

    suspend fun listFiles(targetFilename: String? = null): List<FileContent>

    suspend fun listTrashFiles(): List<FileContent>

    suspend fun listMetadata(): List<FileMetadata>

    suspend fun listTrashMetadata(): List<FileMetadata>

    suspend fun listMetadataWithIds(): List<FileMetadataWithId>

    suspend fun listTrashMetadataWithIds(): List<FileMetadataWithId>

    suspend fun readFileByDocumentId(documentId: String): String?

    suspend fun readTrashFileByDocumentId(documentId: String): String?

    suspend fun readHead(filename: String, maxChars: Int = 256): String?

    suspend fun readHeadByDocumentId(documentId: String, maxChars: Int = 256): String?

    suspend fun readFile(filename: String): String?

    suspend fun readFile(uri: Uri): String?

    suspend fun readTrashFile(filename: String): String?

    suspend fun saveFile(
        filename: String,
        content: String,
        append: Boolean = false,
        uri: Uri? = null,
    ): String?

    suspend fun saveTrashFile(
        filename: String,
        content: String,
        append: Boolean = true,
    )

    suspend fun deleteFile(
        filename: String,
        uri: Uri? = null,
    )

    suspend fun deleteTrashFile(filename: String)

    suspend fun getFileMetadata(filename: String): FileMetadata?

    suspend fun getTrashFileMetadata(filename: String): FileMetadata?

    suspend fun exists(filename: String): Boolean

    suspend fun trashExists(filename: String): Boolean

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

        override suspend fun listFiles(targetFilename: String?): List<FileContent> = getBackend()?.listFiles(targetFilename) ?: emptyList()

        override suspend fun listTrashFiles(): List<FileContent> = getBackend()?.listTrashFiles() ?: emptyList()

        override suspend fun listMetadata(): List<FileMetadata> = getBackend()?.listMetadata() ?: emptyList()

        override suspend fun listTrashMetadata(): List<FileMetadata> = getBackend()?.listTrashMetadata() ?: emptyList()

        override suspend fun listMetadataWithIds(): List<FileMetadataWithId> = getBackend()?.listMetadataWithIds() ?: emptyList()

        override suspend fun listTrashMetadataWithIds(): List<FileMetadataWithId> = getBackend()?.listTrashMetadataWithIds() ?: emptyList()

        override suspend fun readFile(filename: String): String? = getBackend()?.readFile(filename)

        override suspend fun readFile(uri: Uri): String? = getBackend()?.readFile(uri)

        override suspend fun readTrashFile(filename: String): String? = getBackend()?.readTrashFile(filename)

        override suspend fun readFileByDocumentId(documentId: String): String? = getBackend()?.readFileByDocumentId(documentId)

        override suspend fun readTrashFileByDocumentId(documentId: String): String? = getBackend()?.readTrashFileByDocumentId(documentId)

        override suspend fun readHead(filename: String, maxChars: Int): String? = getBackend()?.readHead(filename, maxChars)

        override suspend fun readHeadByDocumentId(documentId: String, maxChars: Int): String? = getBackend()?.readHeadByDocumentId(documentId, maxChars)

        override suspend fun saveFile(
            filename: String,
            content: String,
            append: Boolean,
            uri: Uri?,
        ): String? = getBackend()?.saveFile(filename, content, append, uri)

        override suspend fun saveTrashFile(
            filename: String,
            content: String,
            append: Boolean,
        ) {
            getBackend()?.saveTrashFile(filename, content, append)
        }

        override suspend fun deleteFile(
            filename: String,
            uri: Uri?,
        ) {
            getBackend()?.deleteFile(filename, uri)
        }

        override suspend fun deleteTrashFile(filename: String) {
            getBackend()?.deleteTrashFile(filename)
        }

        override suspend fun getFileMetadata(filename: String): FileMetadata? = getBackend()?.getFileMetadata(filename)

        override suspend fun getTrashFileMetadata(filename: String): FileMetadata? = getBackend()?.getTrashFileMetadata(filename)

        override suspend fun exists(filename: String): Boolean = getBackend()?.exists(filename) ?: false

        override suspend fun trashExists(filename: String): Boolean = getBackend()?.trashExists(filename) ?: false

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
