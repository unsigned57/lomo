package com.lomo.data.source

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class DirectMediaStorageBackendDelegate(
    private val context: Context,
    private val rootDir: File,
) : MediaStorageBackend {
    override suspend fun saveImage(
        sourceUri: Uri,
        filename: String,
    ) {
        directSaveImage(
            context = context,
            rootDir = rootDir,
            sourceUri = sourceUri,
            filename = filename,
        )
    }

    override suspend fun listImageFiles(): List<Pair<String, String>> = directListImageFiles(rootDir)

    override suspend fun getImageLocation(filename: String): String? = directGetImageLocation(rootDir, filename)

    override suspend fun deleteImage(filename: String) {
        // D6: permanent committed-media reclaim is Rust media-trash / orphan sweep only.
        throw UnsupportedOperationException(
            "deleteImage is retired after P4-10A; use MediaRepository.removeImage (media-trash law)",
        )
    }

    override suspend fun createVoiceFile(filename: String): Uri = directCreateVoiceFile(rootDir, filename)

    override suspend fun deleteVoiceFile(filename: String) {
        throw UnsupportedOperationException(
            "deleteVoiceFile is retired after P4-10A; use MediaRepository.removeVoiceCapture (media-trash law)",
        )
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

private suspend fun directCreateVoiceFile(
    rootDir: File,
    filename: String,
): Uri =
    withContext(Dispatchers.IO) {
        directEnsureRootExists(rootDir)
        Uri.fromFile(File(rootDir, filename))
    }

private suspend fun directSaveImage(
    context: Context,
    rootDir: File,
    sourceUri: Uri,
    filename: String,
) = withContext(Dispatchers.IO) {
    val inputStream =
        context.contentResolver.openInputStream(sourceUri)
            ?: throw IOException("Cannot open source image URI")
    inputStream.use { input ->
        directEnsureRootExists(rootDir)
        val targetFile = File(rootDir, filename)
        targetFile.outputStream().use { outputStream ->
            input.copyTo(outputStream)
        }
    }
}
