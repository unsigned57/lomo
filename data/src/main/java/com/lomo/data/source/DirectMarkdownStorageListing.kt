package com.lomo.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun File.relativeLomoPath(rootDir: File): String =
    toRelativeString(rootDir).replace(File.separatorChar, '/')

private fun walkMainMarkdownFiles(rootDir: File): Sequence<File> {
    if (!rootDir.exists() || !rootDir.isDirectory) return emptySequence()
    val trashDir = directTrashDir(rootDir)
    return rootDir
        .walkTopDown()
        .onEnter { dir -> dir != trashDir }
        .filter { file -> file.isFile && file.name.endsWith(DIRECT_MARKDOWN_SUFFIX) }
}

private fun walkTrashMarkdownFiles(rootDir: File): Sequence<File> {
    val trashDir = directTrashDir(rootDir)
    if (!trashDir.exists() || !trashDir.isDirectory) return emptySequence()
    return trashDir
        .listFiles { _, name -> name.endsWith(DIRECT_MARKDOWN_SUFFIX) }
        ?.asSequence()
        ?.filter(File::isFile)
        .orEmpty()
}

internal suspend fun directListMetadata(rootDir: File): List<FileMetadata> =
    withContext(Dispatchers.IO) {
        walkMainMarkdownFiles(rootDir)
            .map { file ->
                FileMetadata(file.relativeLomoPath(rootDir), file.lastModified(), file.length())
            }.toList()
    }

internal suspend fun directListTrashMetadata(rootDir: File): List<FileMetadata> =
    withContext(Dispatchers.IO) {
        walkTrashMarkdownFiles(rootDir)
            .map { file -> FileMetadata(file.name, file.lastModified(), file.length()) }
            .toList()
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
            FileMetadata(filename, file.lastModified(), file.length())
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
            FileMetadata(filename, file.lastModified(), file.length())
        } else {
            null
        }
    }
