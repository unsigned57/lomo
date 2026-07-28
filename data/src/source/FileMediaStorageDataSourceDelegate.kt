package com.lomo.data.source

import android.content.Context
import android.net.Uri
import com.lomo.domain.repository.WorkspaceMutationLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class FileMediaStorageDataSourceDelegate(
    private val context: Context,
    private val backendResolver: FileStorageBackendResolver,
    private val writeLease: WorkspaceMutationLease,
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
        writeLease.withWrite {
            // D6: host storage must not permanently delete committed media.
            throw UnsupportedOperationException(
                "deleteImage is retired after P4-10A; use MediaRepository.removeImage (media-trash law)",
            )
        }
    }

    override suspend fun createVoiceFile(filename: String): Uri =
        writeLease.withWrite {
            (backendResolver.voiceBackend() ?: throw IOException("No storage configured"))
                .createVoiceFile(filename)
        }

    override suspend fun deleteVoiceFile(filename: String) {
        writeLease.withWrite {
            throw UnsupportedOperationException(
                "deleteVoiceFile is retired after P4-10A; use MediaRepository.removeVoiceCapture (media-trash law)",
            )
        }
    }

    private suspend fun resolvedImageBackend(): MediaStorageBackend? =
        backendResolver.resolvedMediaRoot(StorageRootType.IMAGE)?.backend
}
