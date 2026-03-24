package com.lomo.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal suspend fun directListFiles(
    rootDir: File,
    targetFilename: String?,
): List<FileContent> =
    withContext(Dispatchers.IO) {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            return@withContext emptyList()
        }
        rootDir
            .listFiles { _, name ->
                name.endsWith(DIRECT_MARKDOWN_SUFFIX) && (targetFilename == null || name == targetFilename)
            }?.map { file ->
                FileContent(file.name, file.readText(), file.lastModified())
            } ?: emptyList()
    }

internal suspend fun directListTrashFiles(rootDir: File): List<FileContent> =
    withContext(Dispatchers.IO) {
        val trashDir = directTrashDir(rootDir)
        if (!trashDir.exists() || !trashDir.isDirectory) {
            return@withContext emptyList()
        }
        trashDir.listFiles { _, name -> name.endsWith(DIRECT_MARKDOWN_SUFFIX) }?.map { file ->
            FileContent(file.name, file.readText(), file.lastModified())
        } ?: emptyList()
    }

internal suspend fun directListMetadata(rootDir: File): List<FileMetadata> =
    withContext(Dispatchers.IO) {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            return@withContext emptyList()
        }
        rootDir.listFiles { _, name -> name.endsWith(DIRECT_MARKDOWN_SUFFIX) }?.map { file ->
            FileMetadata(file.name, file.lastModified())
        } ?: emptyList()
    }

internal suspend fun directListTrashMetadata(rootDir: File): List<FileMetadata> =
    withContext(Dispatchers.IO) {
        val trashDir = directTrashDir(rootDir)
        if (!trashDir.exists() || !trashDir.isDirectory) {
            return@withContext emptyList()
        }
        trashDir.listFiles { _, name -> name.endsWith(DIRECT_MARKDOWN_SUFFIX) }?.map { file ->
            FileMetadata(file.name, file.lastModified())
        } ?: emptyList()
    }

internal suspend fun directListMetadataWithIds(rootDir: File): List<FileMetadataWithId> =
    directListMetadata(rootDir).map {
        FileMetadataWithId(
            filename = it.filename,
            lastModified = it.lastModified,
            documentId = it.filename,
            uriString = File(rootDir, it.filename).absolutePath,
        )
    }

internal suspend fun directListTrashMetadataWithIds(rootDir: File): List<FileMetadataWithId> {
    val trashDir = directTrashDir(rootDir)
    return directListTrashMetadata(rootDir).map {
        FileMetadataWithId(
            filename = it.filename,
            lastModified = it.lastModified,
            documentId = it.filename,
            uriString = File(trashDir, it.filename).absolutePath,
        )
    }
}

internal suspend fun directGetFileMetadata(
    rootDir: File,
    filename: String,
): FileMetadata? =
    withContext(Dispatchers.IO) {
        val file = File(rootDir, filename)
        if (file.exists()) {
            FileMetadata(filename, file.lastModified())
        } else {
            null
        }
    }

internal suspend fun directGetTrashFileMetadata(
    rootDir: File,
    filename: String,
): FileMetadata? =
    withContext(Dispatchers.IO) {
        val file = File(directTrashDir(rootDir), filename)
        if (file.exists()) {
            FileMetadata(filename, file.lastModified())
        } else {
            null
        }
    }
