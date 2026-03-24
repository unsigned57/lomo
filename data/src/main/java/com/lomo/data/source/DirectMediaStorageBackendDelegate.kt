package com.lomo.data.source

import android.net.Uri
import com.lomo.data.util.runNonFatalCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

internal class DirectMediaStorageBackendDelegate(
    private val rootDir: File,
) : MediaStorageBackend {
    override suspend fun saveImage(
        sourceUri: Uri,
        filename: String,
    ) {
        withContext(Dispatchers.IO) {
            directEnsureRootExists(rootDir)
            throw UnsupportedOperationException(
                "DirectStorageBackend requires Context to resolve source URIs. " +
                    "Use FileDataSourceImpl.saveImage() instead.",
            )
        }
    }

    override suspend fun listImageFiles(): List<Pair<String, String>> = directListImageFiles(rootDir)

    override suspend fun getImageLocation(filename: String): String? = directGetImageLocation(rootDir, filename)

    override suspend fun deleteImage(filename: String) {
        directDeleteMediaFile(rootDir, filename, "image")
    }

    override suspend fun createVoiceFile(filename: String): Uri = directCreateVoiceFile(rootDir, filename)

    override suspend fun deleteVoiceFile(filename: String) {
        directDeleteMediaFile(rootDir, filename, "voice")
    }
}

private suspend fun directListImageFiles(rootDir: File): List<Pair<String, String>> =
    withContext(Dispatchers.IO) {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            return@withContext emptyList()
        }
        rootDir
            .listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && directIsImageFilename(file.name) }
            ?.map { file -> file.name to Uri.fromFile(file).toString() }
            ?.toList()
            ?: emptyList()
    }

private suspend fun directGetImageLocation(
    rootDir: File,
    filename: String,
): String? =
    withContext(Dispatchers.IO) {
        val file = File(rootDir, filename)
        if (file.exists() && file.isFile && directIsImageFilename(file.name)) {
            Uri.fromFile(file).toString()
        } else {
            null
        }
    }

private suspend fun directDeleteMediaFile(
    rootDir: File,
    filename: String,
    mediaType: String,
) = withContext(Dispatchers.IO) {
    runNonFatalCatching {
        val file = File(rootDir, filename)
        if (file.exists()) {
            file.delete()
        }
    }.onFailure { error ->
        Timber.e(error, "Failed to delete %s file: %s", mediaType, filename)
    }
    Unit
}

private suspend fun directCreateVoiceFile(
    rootDir: File,
    filename: String,
): Uri =
    withContext(Dispatchers.IO) {
        directEnsureRootExists(rootDir)
        Uri.fromFile(File(rootDir, filename))
    }
