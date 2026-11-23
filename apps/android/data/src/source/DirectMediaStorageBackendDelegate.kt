package com.lomo.data.source

import android.net.Uri
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files

internal class DirectMediaStorageBackendDelegate(
    private val rootDir: File,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val classifier: AttachmentNameClassifier = NativeAttachmentNameClassifier,
) : MediaStorageBackend {
    private val mediaDispatcher: CoroutineDispatcher = dispatcherProvider.io

    override suspend fun listImageFiles(): List<Pair<String, String>> =
        directListImageFiles(rootDir, mediaDispatcher, classifier)

    override suspend fun getImageLocation(filename: String): String? =
        directGetImageLocation(rootDir, filename, mediaDispatcher)
}

private suspend fun directListImageFiles(
    rootDir: File,
    dispatcher: CoroutineDispatcher,
    classifier: AttachmentNameClassifier,
): List<Pair<String, String>> = withContext(dispatcher) {
    if (!Files.isDirectory(rootDir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("Image directory is unavailable: $rootDir")
    }
    val images = ImageEntrySelector<File>(classifier) { file -> file.name }
    rootDir
        .walkTopDown()
        .onEnter { directory ->
            ensureActive()
            !Files.isSymbolicLink(directory.toPath()) &&
                (directory == rootDir || directory.name !in MEDIA_PRIVATE_DIRECTORIES)
        }
        .onFail { _, error -> throw error }
        .filter { file -> !Files.isSymbolicLink(file.toPath()) && file.isFile }
        .forEach { file ->
            ensureActive()
            images.offer(file)
        }
    images.finish().map { file ->
        file.relativeTo(rootDir).invariantSeparatorsPath to file.toPath().toUri().toASCIIString()
    }
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
