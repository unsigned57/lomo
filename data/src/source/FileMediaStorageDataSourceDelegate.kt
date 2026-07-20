package com.lomo.data.source

import android.content.Context
import android.net.Uri
import com.lomo.data.repository.WorkspaceWriteAuthority

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException


class FileMediaStorageDataSourceDelegate(
    private val context: Context,
    private val backendResolver: FileStorageBackendResolver,
    private val writeAuthority: WorkspaceWriteAuthority,
) : MediaStorageDataSource {
        override suspend fun saveImage(uri: Uri): String =
            withContext(Dispatchers.IO) {
                writeAuthority.requireWritable()
                ImageMagicByteValidator.requireSupportedImage(context.contentResolver, uri)
                val resolvedRoot =
                    backendResolver.resolvedMediaRoot(StorageRootType.IMAGE)
                        ?: throw IOException("No image directory configured")
                val filename = buildImageFilename(uri)
                resolvedRoot.backend.saveImage(uri, filename)
                filename
            }

        override suspend fun listImageFiles(): List<Pair<String, String>> =
            resolvedImageBackend()?.listImageFiles() ?: emptyList()

        override suspend fun getImageLocation(filename: String): String? =
            resolvedImageBackend()?.getImageLocation(filename)

        override suspend fun deleteImage(filename: String) {
            writeAuthority.requireWritable()
            resolvedImageBackend()?.deleteImage(filename)
        }

        override suspend fun createVoiceFile(filename: String): Uri {
            writeAuthority.requireWritable()
            return (backendResolver.voiceBackend() ?: throw IOException("No storage configured"))
                .createVoiceFile(filename)
        }

        override suspend fun deleteVoiceFile(filename: String) {
            writeAuthority.requireWritable()
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
    }
