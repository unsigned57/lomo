package com.lomo.data.source

import android.net.Uri
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files

internal class DirectMediaStorageBackendDelegate(
    private val rootDir: File,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : MediaStorageBackend {
    private val mediaDispatcher: CoroutineDispatcher = dispatcherProvider.io

    override suspend fun listImageFiles(): List<Pair<String, String>> = directListImageFiles(rootDir, mediaDispatcher)

    override suspend fun getImageLocation(filename: String): String? =
        directGetImageLocation(rootDir, filename, mediaDispatcher)
}

private suspend fun directListImageFiles(rootDir: File, dispatcher: CoroutineDispatcher): List<Pair<String, String>> =
    withContext(dispatcher) {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            throw IOException("Image directory is unavailable: $rootDir")
        }
        rootDir
            .walkTopDown()
            .onEnter { directory ->
                !Files.isSymbolicLink(directory.toPath()) &&
                    (directory == rootDir || directory.name !in MEDIA_PRIVATE_DIRECTORIES)
            }
            .onFail { _, error -> throw error }
            .filter { file ->
                !Files.isSymbolicLink(file.toPath()) && file.isFile && isImageAttachmentFileName(file.name)
            }
            .map { file ->
                file.relativeTo(rootDir).invariantSeparatorsPath to file.toPath().toUri().toASCIIString()
            }
            .toList()
    }

private val MEDIA_PRIVATE_DIRECTORIES =
    setOf(".git", ".lomo", ".lomo-media-stage", ".lomo-media-trash", ".lomo-media-delete-intents")

private suspend fun directGetImageLocation(rootDir: File, filename: String, dispatcher: CoroutineDispatcher): String? =
    withContext(dispatcher) {
        val file = File(rootDir, filename)
        if (file.exists() && file.isFile && isImageAttachmentFileName(file.name)) {
            Uri.fromFile(file).toString()
        } else {
            null
        }
    }
