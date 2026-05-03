package com.lomo.data.source

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
        private val backendResolver: FileStorageBackendResolver,
    ) : MediaStorageDataSource {
        override suspend fun saveImage(uri: Uri): String =
            withContext(Dispatchers.IO) {
                ImageMagicByteValidator.requireSupportedImage(context.contentResolver, uri)
                val resolvedRoot =
                    backendResolver.resolvedMediaRoot(StorageRootType.IMAGE)
                        ?: throw IOException("No image directory configured")
                val filename = buildImageFilename(uri)
                when (val vfs = resolvedRoot.vfs) {
                    is WorkspaceVfs.Saf -> resolvedRoot.backend.saveImage(uri, filename)
                    is WorkspaceVfs.Direct -> saveToDirectImageDirectory(vfs.rootDir, uri, filename)
                }
                filename
            }

        override suspend fun listImageFiles(): List<Pair<String, String>> =
            resolvedImageBackend()?.listImageFiles() ?: emptyList()

        override suspend fun getImageLocation(filename: String): String? =
            resolvedImageBackend()?.getImageLocation(filename)

        override suspend fun deleteImage(filename: String) {
            resolvedImageBackend()?.deleteImage(filename)
        }

        override suspend fun createVoiceFile(filename: String): Uri =
            (backendResolver.voiceBackend() ?: throw IOException("No storage configured")).createVoiceFile(filename)

        override suspend fun deleteVoiceFile(filename: String) {
            backendResolver.voiceBackend()?.deleteVoiceFile(filename)
        }

        private suspend fun resolvedImageBackend(): MediaStorageBackend? =
            backendResolver.resolvedMediaRoot(StorageRootType.IMAGE)?.backend

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
            rootDir: File,
            uri: Uri,
            filename: String,
        ) {
            val inputStream =
                context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open source image URI")
            if (!rootDir.exists()) {
                rootDir.mkdirs()
            }
            val targetFile = File(rootDir, filename)
            targetFile.outputStream().use { outputStream ->
                inputStream.use { input -> input.copyTo(outputStream) }
            }
        }
    }
