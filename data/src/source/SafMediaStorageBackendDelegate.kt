package com.lomo.data.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.util.runNonFatalCatching
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

internal class SafMediaStorageBackendDelegate(
    private val context: Context,
    private val documentAccess: SafDocumentAccess,
) : MediaStorageBackend {
    override suspend fun saveImage(
        sourceUri: Uri,
        filename: String,
    ) {
        withContext(SAF_IO_DISPATCHER) {
            val root = requireImageRoot(documentAccess)
            val inputStream = openRequiredInputStream(context, sourceUri)
            val extension = filename.substringAfterLast(".", "jpg")
            val newFile = createImageFile(root, filename, extension)
            openRequiredOutputStream(context, newFile.uri).use { outputStream ->
                inputStream.use { input -> input.copyTo(outputStream) }
            }
        }
    }

    override suspend fun listImageFiles(): List<Pair<String, String>> = safListImageFiles(documentAccess)

    override suspend fun getImageLocation(filename: String): String? = safGetImageLocation(documentAccess, filename)

    override suspend fun deleteImage(filename: String) {
        safDeleteMediaFile(documentAccess, filename, "image")
    }

    override suspend fun createVoiceFile(filename: String): Uri = safCreateVoiceFile(documentAccess, filename)

    override suspend fun deleteVoiceFile(filename: String) {
        safDeleteMediaFile(documentAccess, filename, "voice")
    }
}

private suspend fun safListImageFiles(
    documentAccess: SafDocumentAccess,
): List<Pair<String, String>> =
    withContext(SAF_IO_DISPATCHER) {
        val root = documentAccess.root() ?: return@withContext emptyList()
        root.listFiles().mapNotNull { file ->
            val name = file.name
            if (name == null || !file.isFile) {
                return@mapNotNull null
            }
            val mimeType = file.type
            if (mimeType?.startsWith("image/") == true || safIsImageFilename(name)) {
                name to file.uri.toString()
            } else {
                null
            }
        }
    }

private suspend fun safGetImageLocation(
    documentAccess: SafDocumentAccess,
    filename: String,
): String? =
    withContext(SAF_IO_DISPATCHER) {
        documentAccess.root()?.findFile(filename)?.takeIf { it.isFile }?.uri?.toString()
    }

private suspend fun safDeleteMediaFile(
    documentAccess: SafDocumentAccess,
    filename: String,
    mediaType: String,
) = withContext(SAF_IO_DISPATCHER) {
    runNonFatalCatching {
        documentAccess.root()?.findFile(filename)?.delete()
    }.onFailure { error ->
        Timber.e(error, "Failed to delete %s: %s", mediaType, filename)
    }
    Unit
}

private fun requireImageRoot(documentAccess: SafDocumentAccess): DocumentFile =
    documentAccess.root() ?: throw IOException("Cannot access image directory")

private fun openRequiredInputStream(
    context: Context,
    sourceUri: Uri,
) = context.contentResolver.openInputStream(sourceUri)
    ?: throw IOException("Cannot open source image URI")

private fun createImageFile(
    root: DocumentFile,
    filename: String,
    extension: String,
): DocumentFile =
    root.createFile("image/$extension", filename)
        ?: throw IOException("Cannot create image file")

private fun openRequiredOutputStream(
    context: Context,
    uri: Uri,
) = context.contentResolver.openOutputStream(uri)
    ?: throw IOException("Cannot write to image file")

private suspend fun safCreateVoiceFile(
    documentAccess: SafDocumentAccess,
    filename: String,
): Uri =
    withContext(SAF_IO_DISPATCHER) {
        val root = documentAccess.root() ?: throw IOException("Cannot access voice directory")
        val extension = filename.substringAfterLast('.', "m4a")
        val mimeType =
            when (extension) {
                "m4a" -> "audio/mp4"
                "mp3" -> "audio/mpeg"
                "aac" -> "audio/aac"
                else -> "audio/mp4"
            }
        val file = root.createFile(mimeType, filename) ?: throw IOException("Cannot create voice file")
        file.uri
    }
