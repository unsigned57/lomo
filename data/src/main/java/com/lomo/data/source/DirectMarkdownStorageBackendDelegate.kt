package com.lomo.data.source

import android.net.Uri
import java.io.File

internal class DirectMarkdownStorageBackendDelegate(
    private val rootDir: File,
) : MarkdownStorageBackend {
    override suspend fun listFilesIn(
        directory: MemoDirectoryType,
        targetFilename: String?,
    ): List<FileContent> =
        when (directory) {
            MemoDirectoryType.MAIN -> directListFiles(rootDir, targetFilename)
            MemoDirectoryType.TRASH -> directListTrashFiles(rootDir)
        }

    override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> =
        when (directory) {
            MemoDirectoryType.MAIN -> directListMetadata(rootDir)
            MemoDirectoryType.TRASH -> directListTrashMetadata(rootDir)
        }

    override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> =
        when (directory) {
            MemoDirectoryType.MAIN -> directListMetadataWithIds(rootDir)
            MemoDirectoryType.TRASH -> directListTrashMetadataWithIds(rootDir)
        }

    override suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): FileMetadata? =
        when (directory) {
            MemoDirectoryType.MAIN -> directGetFileMetadata(rootDir, filename)
            MemoDirectoryType.TRASH -> directGetTrashFileMetadata(rootDir, filename)
        }

    override suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> directReadFile(rootDir, filename)
            MemoDirectoryType.TRASH -> directReadTrashFile(rootDir, filename)
        }

    override suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> directReadFile(rootDir, documentId)
            MemoDirectoryType.TRASH -> directReadTrashFile(rootDir, documentId)
        }

    override suspend fun readFile(uri: Uri): String? = directReadFileUri(uri)

    override suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean,
        uri: Uri?,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> directSaveFile(rootDir, filename, content, append)
            MemoDirectoryType.TRASH -> {
                directSaveTrashFile(rootDir, filename, content, append)
                null
            }
        }

    override suspend fun deleteFileIn(
        directory: MemoDirectoryType,
        filename: String,
        uri: Uri?,
    ) {
        when (directory) {
            MemoDirectoryType.MAIN -> directDeleteFile(rootDir, filename)
            MemoDirectoryType.TRASH -> directDeleteTrashFile(rootDir, filename)
        }
    }
}
