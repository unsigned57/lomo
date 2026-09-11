package com.lomo.data.source

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class DirectMediaStorageBackendDelegate(
    private val rootDir: File,
) : MediaStorageBackend {
    override suspend fun listImageFiles(): List<Pair<String, String>> = directListImageFiles(rootDir)

    override suspend fun getImageLocation(filename: String): String? = directGetImageLocation(rootDir, filename)
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
