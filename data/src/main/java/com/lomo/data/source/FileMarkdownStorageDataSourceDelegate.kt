package com.lomo.data.source

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileMarkdownStorageDataSourceDelegate
    @Inject
    constructor(
        private val backendResolver: FileStorageBackendResolver,
    ) : MarkdownStorageDataSource {
        override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> =
            backendResolver.markdownBackend()?.listMetadataIn(directory) ?: emptyList()

        override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> =
            backendResolver.markdownBackend()?.listMetadataWithIdsIn(directory) ?: emptyList()

        override fun streamMetadataWithIdsIn(directory: MemoDirectoryType): Flow<FileMetadataWithId> =
            flow {
                emitAll(backendResolver.markdownBackend()?.streamMetadataWithIdsIn(directory) ?: emptyFlow())
            }

        override suspend fun readFileByDocumentIdIn(
            directory: MemoDirectoryType,
            documentId: String,
        ): String? = backendResolver.markdownBackend()?.readFileByDocumentIdIn(directory, documentId)

        override fun streamFileByDocumentIdIn(
            directory: MemoDirectoryType,
            documentId: String,
        ): Flow<String> =
            flow {
                emitAll(
                    backendResolver.markdownBackend()?.streamFileByDocumentIdIn(directory, documentId) ?: emptyFlow(),
                )
            }

        override suspend fun readFileIn(
            directory: MemoDirectoryType,
            filename: String,
        ): String? = backendResolver.markdownBackend()?.readFileIn(directory, filename)

        override suspend fun fingerprintFileIn(
            directory: MemoDirectoryType,
            filename: String,
        ): String? = backendResolver.markdownBackend()?.fingerprintFileIn(directory, filename)

        override suspend fun readFile(uri: Uri): String? = backendResolver.markdownBackend()?.readFile(uri)

        override suspend fun saveFileIn(
            directory: MemoDirectoryType,
            filename: String,
            content: String,
            append: Boolean,
            uri: Uri?,
        ): String? =
            requireMarkdownBackend("saveFileIn($filename)")
                .saveFileIn(directory, filename, content, append, uri)

        override suspend fun deleteFileIn(
            directory: MemoDirectoryType,
            filename: String,
            uri: Uri?,
        ) {
            requireMarkdownBackend("deleteFileIn($filename)").deleteFileIn(directory, filename, uri)
        }

        override suspend fun getFileMetadataIn(
            directory: MemoDirectoryType,
            filename: String,
        ): FileMetadata? = backendResolver.markdownBackend()?.getFileMetadataIn(directory, filename)

        /**
         * Writes must never silently no-op: a missing backend means the workspace root is not
         * configured/accessible, which is an invalid state for a mutation, not a successful empty
         * write. Surfacing it lets the outbox treat the flush as a retryable failure instead of
         * acknowledging a write that never reached the source files.
         */
        private suspend fun requireMarkdownBackend(operation: String): MarkdownStorageBackend =
            backendResolver.markdownBackend()
                ?: error("Workspace storage backend is not configured; cannot $operation")
    }
