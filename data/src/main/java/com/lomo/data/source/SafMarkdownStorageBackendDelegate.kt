package com.lomo.data.source

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow

internal class SafMarkdownStorageBackendDelegate(
    private val context: Context,
    private val rootUri: Uri,
    private val documentAccess: SafDocumentAccess,
) : MarkdownStorageBackend {
    override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { safListMetadata(context, rootUri, documentAccess) },
            onTrash = { safListTrashMetadata(documentAccess) },
        )

    override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { safListMetadataWithIds(context, rootUri, documentAccess) },
            onTrash = { safListTrashMetadataWithIds(context, rootUri, documentAccess) },
        )

    override fun streamMetadataWithIdsIn(directory: MemoDirectoryType): Flow<FileMetadataWithId> =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { safStreamMetadataWithIds(context, rootUri, documentAccess) },
            onTrash = { safStreamTrashMetadataWithIds(context, rootUri, documentAccess) },
        )

    override suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): FileMetadata? =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { safGetFileMetadata(documentAccess, filename) },
            onTrash = { safGetTrashFileMetadata(documentAccess, filename) },
        )

    override suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String? =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { safReadFile(documentAccess, filename) },
            onTrash = { safReadTrashFile(documentAccess, filename) },
        )

    override suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String? =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { safReadFileByDocumentId(rootUri, documentAccess, documentId) },
            onTrash = { safReadTrashFileByDocumentId(rootUri, documentAccess, documentId) },
        )

    override fun streamFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): Flow<String> =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { safStreamFileByDocumentId(rootUri, documentAccess, documentId) },
            onTrash = { safStreamTrashFileByDocumentId(rootUri, documentAccess, documentId) },
        )

    override suspend fun readFile(uri: Uri): String? = safReadFileUri(documentAccess, uri)

    override suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean,
        uri: Uri?,
    ): String? =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { safSaveFile(rootUri, documentAccess, filename, content, append, uri) },
            onTrash = {
                safSaveTrashFile(documentAccess, filename, content, append)
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
            onMain = { safDeleteFile(context, documentAccess, filename, uri) },
            onTrash = { safDeleteTrashFile(documentAccess, filename) },
        )
    }
}
