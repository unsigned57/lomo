package com.lomo.data.source

import android.content.Context
import android.net.Uri
import com.lomo.data.local.datastore.LomoDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileMediaStorageDataSourceDelegate
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataStore: LomoDataStore,
        private val backendResolver: FileStorageBackendResolver,
    ) : MediaStorageDataSource {
        override suspend fun saveImage(uri: Uri): String =
            withContext(Dispatchers.IO) {
                val (backend, _) = backendResolver.mediaBackend(StorageRootType.IMAGE)
                val filename = buildImageFilename(uri)
                when (backend) {
                    is SafStorageBackend -> backend.saveImage(uri, filename)
                    is DirectStorageBackend -> saveToDirectImageDirectory(uri, filename)
                    null -> throw IOException("No image directory configured")
                }
                filename
            }

        override suspend fun listImageFiles(): List<Pair<String, String>> =
            backendResolver.mediaBackend(StorageRootType.IMAGE).first?.listImageFiles() ?: emptyList()

        override suspend fun getImageLocation(filename: String): String? =
            backendResolver.mediaBackend(StorageRootType.IMAGE).first?.getImageLocation(filename)

        override suspend fun deleteImage(filename: String) {
            backendResolver.mediaBackend(StorageRootType.IMAGE).first?.deleteImage(filename)
        }

        override suspend fun createVoiceFile(filename: String): Uri =
            (backendResolver.voiceBackend() ?: throw IOException("No storage configured")).createVoiceFile(filename)

        override suspend fun deleteVoiceFile(filename: String) {
            backendResolver.voiceBackend()?.deleteVoiceFile(filename)
        }

        private fun buildImageFilename(uri: Uri): String {
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
            return "img_$timestamp.$extension"
        }

        private suspend fun saveToDirectImageDirectory(
            uri: Uri,
            filename: String,
        ) {
            val imageDirectory = dataStore.imageDirectory.first() ?: throw IOException("No image directory configured")
            val inputStream =
                context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open source image URI")
            val targetDir = File(imageDirectory)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val targetFile = File(targetDir, filename)
            targetFile.outputStream().use { outputStream ->
                inputStream.use { input -> input.copyTo(outputStream) }
            }
        }
    }
