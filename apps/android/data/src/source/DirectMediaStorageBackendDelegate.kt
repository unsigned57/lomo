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
) : MediaStorageBackend {
    private val mediaDispatcher: CoroutineDispatcher = dispatcherProvider.io

    override suspend fun listImageFiles(scope: ImageScanScope): List<Pair<String, String>> =
        directListImageFiles(rootDir, mediaDispatcher, scope)

    override suspend fun getImageLocation(filename: String): String? =
        directGetImageLocation(rootDir, filename, mediaDispatcher)
}

private suspend fun directListImageFiles(
    rootDir: File,
    dispatcher: CoroutineDispatcher,
    scope: ImageScanScope,
): List<Pair<String, String>> = withContext(dispatcher) {
    if (!Files.isDirectory(rootDir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("Image directory is unavailable: $rootDir")
    }
    rootDir
        .walkTopDown()
        .onEnter { directory ->
            ensureActive()
            !Files.isSymbolicLink(directory.toPath()) &&
                (
                    directory == rootDir || (
                        directory.name !in MEDIA_PRIVATE_DIRECTORIES &&
                            scope.includesDirectory(directory.relativeTo(rootDir).invariantSeparatorsPath)
                        )
                    )
        }
        .onFail { _, error -> throw error }
        .filter { file ->
            !Files.isSymbolicLink(file.toPath()) && file.isFile && isImageAttachmentFileName(file.name)
        }
        .map { file ->
            ensureActive()
            file.relativeTo(rootDir).invariantSeparatorsPath to file.toPath().toUri().toASCIIString()
        }
        .filter { (path, _) -> scope.includesFile(path) }
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
