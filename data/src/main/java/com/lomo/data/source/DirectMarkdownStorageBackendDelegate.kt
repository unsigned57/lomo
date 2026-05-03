package com.lomo.data.source

import android.net.Uri
import java.io.File

internal class DirectMarkdownStorageBackendDelegate(
    private val rootDir: File,
    private val secureWipeBeforeDeleteEnabled: suspend () -> Boolean = { false },
) : MarkdownStorageBackend {
    override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directListMetadata(rootDir) },
            onTrash = { directListTrashMetadata(rootDir) },
        )

    override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directListMetadataWithIds(rootDir) },
            onTrash = { directListTrashMetadataWithIds(rootDir) },
        )

    override suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): FileMetadata? =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directGetFileMetadata(rootDir, filename) },
            onTrash = { directGetTrashFileMetadata(rootDir, filename) },
        )

    override suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String? =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directReadFile(rootDir, filename) },
            onTrash = { directReadTrashFile(rootDir, filename) },
        )

    override suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String? =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directReadFile(rootDir, documentId) },
            onTrash = { directReadTrashFile(rootDir, documentId) },
        )

    override suspend fun readFile(uri: Uri): String? = directReadFileUri(uri)

    override suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean,
        uri: Uri?,
    ): String? =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directSaveFile(rootDir, filename, content, append) },
            onTrash = {
                directSaveTrashFile(rootDir, filename, content, append)
                null
            },
        )

    override suspend fun deleteFileIn(
        directory: MemoDirectoryType,
        filename: String,
        uri: Uri?,
    ) {
        routeMarkdownDirectory(
            directory = directory,
            onMain = {
                directDeleteFile(
                    rootDir = rootDir,
                    filename = filename,
                    overwriteBeforeUnlink = secureWipeBeforeDeleteEnabled(),
                )
            },
            onTrash = {
                directDeleteTrashFile(
                    rootDir = rootDir,
                    filename = filename,
                    overwriteBeforeUnlink = secureWipeBeforeDeleteEnabled(),
                )
            },
        )
    }
}
