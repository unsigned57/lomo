package com.lomo.data.source

import android.net.Uri
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

internal class DirectMediaStorageBackendDelegate(
    private val rootDir: File,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : MediaStorageBackend {
    private val mediaDispatcher: CoroutineDispatcher = dispatcherProvider.io

    override suspend fun listImageFiles(): List<Pair<String, String>> =
        directListImageFiles(rootDir, mediaDispatcher)

    override suspend fun getImageLocation(filename: String): String? =
        directGetImageLocation(rootDir, filename, mediaDispatcher)
}

private suspend fun directListImageFiles(
    rootDir: File,
    dispatcher: CoroutineDispatcher,
): List<Pair<String, String>> =
    withContext(dispatcher) {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            return@withContext emptyList()
        }
        rootDir
            .listFiles()
            ?.run {
                asSequence()
                    .filter { file -> file.isFile && directIsImageFilename(file.name) }
                    .map { file -> file.name to Uri.fromFile(file).toString() }
                    .toList()
            }
            .orEmpty()
    }

private suspend fun directGetImageLocation(
    rootDir: File,
    filename: String,
    dispatcher: CoroutineDispatcher,
): String? =
    withContext(dispatcher) {
        val file = File(rootDir, filename)
        if (file.exists() && file.isFile && directIsImageFilename(file.name)) {
            Uri.fromFile(file).toString()
        } else {
            null
        }
    }
