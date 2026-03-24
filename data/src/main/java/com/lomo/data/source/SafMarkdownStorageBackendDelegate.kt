package com.lomo.data.source

import android.content.Context
import android.net.Uri

internal class SafMarkdownStorageBackendDelegate(
    private val context: Context,
    private val rootUri: Uri,
    private val documentAccess: SafDocumentAccess,
) : MarkdownStorageBackend {
    override suspend fun listFilesIn(
        directory: MemoDirectoryType,
        targetFilename: String?,
    ): List<FileContent> =
        when (directory) {
            MemoDirectoryType.MAIN -> safListFiles(documentAccess, targetFilename)
            MemoDirectoryType.TRASH -> safListTrashFiles(documentAccess)
        }

    override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> =
        when (directory) {
            MemoDirectoryType.MAIN -> safListMetadata(context, rootUri, documentAccess)
            MemoDirectoryType.TRASH -> safListTrashMetadata(documentAccess)
        }

    override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> =
        when (directory) {
            MemoDirectoryType.MAIN -> safListMetadataWithIds(context, rootUri, documentAccess)
            MemoDirectoryType.TRASH -> safListTrashMetadataWithIds(context, rootUri, documentAccess)
        }

    override suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): FileMetadata? =
        when (directory) {
            MemoDirectoryType.MAIN -> safGetFileMetadata(documentAccess, filename)
            MemoDirectoryType.TRASH -> safGetTrashFileMetadata(documentAccess, filename)
        }

    override suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> safReadFile(documentAccess, filename)
            MemoDirectoryType.TRASH -> safReadTrashFile(documentAccess, filename)
        }

    override suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> safReadFileByDocumentId(rootUri, documentAccess, documentId)
            MemoDirectoryType.TRASH -> safReadTrashFileByDocumentId(rootUri, documentAccess, documentId)
        }

    override suspend fun readFile(uri: Uri): String? = safReadFileUri(documentAccess, uri)

    override suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean,
        uri: Uri?,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> safSaveFile(rootUri, documentAccess, filename, content, append, uri)
            MemoDirectoryType.TRASH -> {
                safSaveTrashFile(documentAccess, filename, content, append)
                null
            }
        }

    override suspend fun deleteFileIn(
        directory: MemoDirectoryType,
        filename: String,
        uri: Uri?,
    ) {
        when (directory) {
            MemoDirectoryType.MAIN -> safDeleteFile(context, documentAccess, filename, uri)
            MemoDirectoryType.TRASH -> safDeleteTrashFile(documentAccess, filename)
        }
    }
}
