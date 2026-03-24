package com.lomo.data.source

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileMarkdownStorageDataSourceDelegate
    @Inject
    constructor(
        private val backendResolver: FileStorageBackendResolver,
    ) : MarkdownStorageDataSource {
        override suspend fun listFilesIn(
            directory: MemoDirectoryType,
            targetFilename: String?,
        ): List<FileContent> = backendResolver.markdownBackend()?.listFilesIn(directory, targetFilename) ?: emptyList()

        override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> =
            backendResolver.markdownBackend()?.listMetadataIn(directory) ?: emptyList()

        override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> =
            backendResolver.markdownBackend()?.listMetadataWithIdsIn(directory) ?: emptyList()

        override suspend fun readFileByDocumentIdIn(
            directory: MemoDirectoryType,
            documentId: String,
        ): String? = backendResolver.markdownBackend()?.readFileByDocumentIdIn(directory, documentId)

        override suspend fun readFileIn(
            directory: MemoDirectoryType,
            filename: String,
        ): String? = backendResolver.markdownBackend()?.readFileIn(directory, filename)

        override suspend fun readFile(uri: Uri): String? = backendResolver.markdownBackend()?.readFile(uri)

        override suspend fun saveFileIn(
            directory: MemoDirectoryType,
            filename: String,
            content: String,
            append: Boolean,
            uri: Uri?,
        ): String? = backendResolver.markdownBackend()?.saveFileIn(directory, filename, content, append, uri)

        override suspend fun deleteFileIn(
            directory: MemoDirectoryType,
            filename: String,
            uri: Uri?,
        ) {
            backendResolver.markdownBackend()?.deleteFileIn(directory, filename, uri)
        }

        override suspend fun getFileMetadataIn(
            directory: MemoDirectoryType,
            filename: String,
        ): FileMetadata? = backendResolver.markdownBackend()?.getFileMetadataIn(directory, filename)
    }
