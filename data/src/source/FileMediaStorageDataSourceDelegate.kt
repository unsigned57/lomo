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
            // Wave A: image identity/import is owned by MediaEdgeRepository → MediaPort (Rust).
            // This data-source path must not dual-own magic or invent basenames.
            throw IOException(
                "saveImage is retired after P4-10A; use MediaRepository.importImage (Rust media owner)",
            )
        }

    override suspend fun listImageFiles(): List<Pair<String, String>> =
        resolvedImageBackend()?.listImageFiles() ?: emptyList()

    override suspend fun getImageLocation(filename: String): String? =
        resolvedImageBackend()?.getImageLocation(filename)

    override suspend fun deleteImage(filename: String) {
        writeAuthority.requireWritable()
        // D6: host storage must not permanently delete committed media.
        throw UnsupportedOperationException(
            "deleteImage is retired after P4-10A; use MediaRepository.removeImage (media-trash law)",
        )
    }

    override suspend fun createVoiceFile(filename: String): Uri {
        writeAuthority.requireWritable()
        return (backendResolver.voiceBackend() ?: throw IOException("No storage configured"))
            .createVoiceFile(filename)
    }

    override suspend fun deleteVoiceFile(filename: String) {
        writeAuthority.requireWritable()
        throw UnsupportedOperationException(
            "deleteVoiceFile is retired after P4-10A; use MediaRepository.removeVoiceCapture (media-trash law)",
        )
    }

    private suspend fun resolvedImageBackend(): MediaStorageBackend? =
        backendResolver.resolvedMediaRoot(StorageRootType.IMAGE)?.backend
}
