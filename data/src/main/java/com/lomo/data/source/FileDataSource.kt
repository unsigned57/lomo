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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

interface WorkspaceConfigSource {
    /**
     * Unified root configuration API.
     * Wrappers (`setRoot`, `setImageRoot`, `setVoiceRoot`) are kept for compatibility.
     */
    suspend fun setRoot(
        type: StorageRootType,
        pathOrUri: String,
    )

    fun getRootFlow(type: StorageRootType): Flow<String?>

    fun getRootDisplayNameFlow(type: StorageRootType): Flow<String?>

    suspend fun setRoot(pathOrUri: String) = setRoot(StorageRootType.MAIN, pathOrUri)

    fun getRootFlow(): Flow<String?> = getRootFlow(StorageRootType.MAIN)

    fun getRootDisplayNameFlow(): Flow<String?> = getRootDisplayNameFlow(StorageRootType.MAIN)

    suspend fun setImageRoot(pathOrUri: String) = setRoot(StorageRootType.IMAGE, pathOrUri)

    fun getImageRootFlow(): Flow<String?> = getRootFlow(StorageRootType.IMAGE)

    fun getImageRootDisplayNameFlow(): Flow<String?> = getRootDisplayNameFlow(StorageRootType.IMAGE)

    suspend fun setVoiceRoot(pathOrUri: String) = setRoot(StorageRootType.VOICE, pathOrUri)

    fun getVoiceRootFlow(): Flow<String?> = getRootFlow(StorageRootType.VOICE)

    fun getVoiceRootDisplayNameFlow(): Flow<String?> = getRootDisplayNameFlow(StorageRootType.VOICE)

    suspend fun createDirectory(name: String): String
}

interface MarkdownStorageDataSource {
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
}

interface MediaStorageDataSource {
    suspend fun saveImage(uri: Uri): String

    suspend fun listImageFiles(): List<Pair<String, String>>

    suspend fun deleteImage(filename: String)

    suspend fun createVoiceFile(filename: String): Uri

    suspend fun deleteVoiceFile(filename: String)
}

interface FileDataSource :
    WorkspaceConfigSource,
    MarkdownStorageDataSource,
    MediaStorageDataSource

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
 * Delegates file operations to split storage backends:
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

        override suspend fun setRoot(
            type: StorageRootType,
            pathOrUri: String,
        ) {
            if (isContentUri(pathOrUri)) {
                updateRootValues(
                    type = type,
                    uri = pathOrUri,
                    path = null,
                )
            } else {
                updateRootValues(
                    type = type,
                    uri = null,
                    path = pathOrUri,
                )
            }
        }

        override fun getRootFlow(type: StorageRootType): Flow<String?> =
            combine(readRootUriFlow(type), readRootPathFlow(type)) { uri, path -> uri ?: path }

        override fun getRootDisplayNameFlow(type: StorageRootType): Flow<String?> =
            getRootFlow(type).map { uriOrPath ->
                when {
                    uriOrPath == null -> null
                    isContentUri(uriOrPath) -> getDisplayName(Uri.parse(uriOrPath))
                    else -> uriOrPath
                }
            }

        private fun getDisplayName(uri: Uri): String =
            try {
                DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: uri.toString()
            } catch (e: Exception) {
                uri.lastPathSegment ?: uri.toString()
            }

        private fun isContentUri(value: String): Boolean =
            runCatching {
                java.net
                    .URI(value)
                    .scheme
                    .equals("content", ignoreCase = true)
            }.getOrDefault(false)

        private suspend fun updateRootValues(
            type: StorageRootType,
            uri: String?,
            path: String?,
        ) {
            when (type) {
                StorageRootType.MAIN -> {
                    dataStore.updateRootUri(uri)
                    dataStore.updateRootDirectory(path)
                }

                StorageRootType.IMAGE -> {
                    dataStore.updateImageUri(uri)
                    dataStore.updateImageDirectory(path)
                }

                StorageRootType.VOICE -> {
                    dataStore.updateVoiceUri(uri)
                    dataStore.updateVoiceDirectory(path)
                }
            }
        }

        private fun readRootUriFlow(type: StorageRootType): Flow<String?> =
            when (type) {
                StorageRootType.MAIN -> dataStore.rootUri
                StorageRootType.IMAGE -> dataStore.imageUri
                StorageRootType.VOICE -> dataStore.voiceUri
            }

        private fun readRootPathFlow(type: StorageRootType): Flow<String?> =
            when (type) {
                StorageRootType.MAIN -> dataStore.rootDirectory
                StorageRootType.IMAGE -> dataStore.imageDirectory
                StorageRootType.VOICE -> dataStore.voiceDirectory
            }

        // --- Backend resolution ---

        private val backendCacheMutex = Mutex()
        private var currentMarkdownBackend: MarkdownStorageBackend? = null
        private var currentWorkspaceBackend: WorkspaceConfigBackend? = null
        private var currentRootConfig: String? = null

        private suspend fun getMarkdownBackend(): MarkdownStorageBackend? =
            backendCacheMutex.withLock {
                resolveMarkdownBackendLocked()
            }

        private suspend fun resolveMarkdownBackendLocked(): MarkdownStorageBackend? {
            val rootUri = dataStore.rootUri.first()
            val rootDir = dataStore.rootDirectory.first()

            val configKey = rootUri ?: rootDir

            // Return cached backend if configuration hasn't changed
            if (currentMarkdownBackend != null && currentRootConfig == configKey) {
                return currentMarkdownBackend
            }

            val markdownBackend: MarkdownStorageBackend?
            val workspaceBackend: WorkspaceConfigBackend?
            when {
                rootUri != null -> {
                    val backend = SafStorageBackend(context, Uri.parse(rootUri))
                    markdownBackend = backend
                    workspaceBackend = backend
                }

                rootDir != null -> {
                    val backend = DirectStorageBackend(java.io.File(rootDir))
                    markdownBackend = backend
                    workspaceBackend = backend
                }

                else -> {
                    markdownBackend = null
                    workspaceBackend = null
                }
            }

            currentMarkdownBackend = markdownBackend
            currentWorkspaceBackend = workspaceBackend
            currentRootConfig = configKey
            return currentMarkdownBackend
        }

        private suspend fun getWorkspaceBackend(): WorkspaceConfigBackend? =
            backendCacheMutex.withLock {
                resolveMarkdownBackendLocked()
                currentWorkspaceBackend
            }

        private suspend fun getImageBackend(): Pair<MediaStorageBackend?, Uri?> {
            val (backend, uriString) = getConfiguredBackend(StorageRootType.IMAGE)
            return backend to uriString?.let(Uri::parse)
        }

        private suspend fun getConfiguredBackend(type: StorageRootType): Pair<MediaStorageBackend?, String?> {
            val configuredUri = readRootUriFlow(type).first()
            val configuredPath = readRootPathFlow(type).first()
            return when {
                configuredUri != null -> SafStorageBackend(context, Uri.parse(configuredUri)) to configuredUri
                configuredPath != null -> DirectStorageBackend(java.io.File(configuredPath)) to null
                else -> null to null
            }
        }

        // --- Delegated file operations ---

        override suspend fun listFilesIn(
            directory: MemoDirectoryType,
            targetFilename: String?,
        ): List<FileContent> = getMarkdownBackend()?.listFilesIn(directory, targetFilename) ?: emptyList()

        override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> =
            getMarkdownBackend()?.listMetadataIn(directory) ?: emptyList()

        override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> =
            getMarkdownBackend()?.listMetadataWithIdsIn(directory) ?: emptyList()

        override suspend fun readFile(uri: Uri): String? = getMarkdownBackend()?.readFile(uri)

        override suspend fun readFileByDocumentIdIn(
            directory: MemoDirectoryType,
            documentId: String,
        ): String? = getMarkdownBackend()?.readFileByDocumentIdIn(directory, documentId)

        override suspend fun readHeadIn(
            directory: MemoDirectoryType,
            filename: String,
            maxChars: Int,
        ): String? = getMarkdownBackend()?.readHeadIn(directory, filename, maxChars)

        override suspend fun readHeadByDocumentIdIn(
            directory: MemoDirectoryType,
            documentId: String,
            maxChars: Int,
        ): String? = getMarkdownBackend()?.readHeadByDocumentIdIn(directory, documentId, maxChars)

        override suspend fun readFileIn(
            directory: MemoDirectoryType,
            filename: String,
        ): String? = getMarkdownBackend()?.readFileIn(directory, filename)

        override suspend fun saveFileIn(
            directory: MemoDirectoryType,
            filename: String,
            content: String,
            append: Boolean,
            uri: Uri?,
        ): String? = getMarkdownBackend()?.saveFileIn(directory, filename, content, append, uri)

        override suspend fun deleteFileIn(
            directory: MemoDirectoryType,
            filename: String,
            uri: Uri?,
        ) {
            getMarkdownBackend()?.deleteFileIn(directory, filename, uri)
        }

        override suspend fun getFileMetadataIn(
            directory: MemoDirectoryType,
            filename: String,
        ): FileMetadata? = getMarkdownBackend()?.getFileMetadataIn(directory, filename)

        override suspend fun existsIn(
            directory: MemoDirectoryType,
            filename: String,
        ): Boolean = getMarkdownBackend()?.existsIn(directory, filename) ?: false

        // --- Image operations (require special handling for source URI resolution) ---

        override suspend fun saveImage(uri: Uri): String =
            withContext(Dispatchers.IO) {
                val (backend, _) = getImageBackend()

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

        private suspend fun getVoiceBackend(): MediaStorageBackend? {
            val (voiceBackend, _) = getConfiguredBackend(StorageRootType.VOICE)

            // Use custom voice directory if set
            if (voiceBackend != null) return voiceBackend

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
            getWorkspaceBackend()?.createDirectory(name)
                ?: throw java.io.IOException("No storage configured")
    }
