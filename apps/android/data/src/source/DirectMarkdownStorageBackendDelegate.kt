package com.lomo.data.source

import android.net.Uri
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import java.io.File

internal class DirectMarkdownStorageBackendDelegate(
    private val rootDir: File,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : MarkdownStorageBackend {
    override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directListMetadata(rootDir, dispatcherProvider) },
            onTrash = { directListTrashMetadata(rootDir, dispatcherProvider) },
        )

    override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directListMetadataWithIds(rootDir, dispatcherProvider) },
            onTrash = { directListTrashMetadataWithIds(rootDir, dispatcherProvider) },
        )

    override fun streamMetadataWithIdsIn(directory: MemoDirectoryType): Flow<FileMetadataWithId> =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directStreamMetadataWithIds(rootDir, dispatcherProvider) },
            onTrash = { directStreamTrashMetadataWithIds(rootDir, dispatcherProvider) },
        )

    override suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): FileMetadata? =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directGetFileMetadata(rootDir, filename, dispatcherProvider) },
            onTrash = { directGetTrashFileMetadata(rootDir, filename, dispatcherProvider) },
        )

    override suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String? =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directReadFile(rootDir, filename, dispatcherProvider) },
            onTrash = { directReadTrashFile(rootDir, filename, dispatcherProvider) },
        )

    override suspend fun fingerprintFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String? =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directFingerprintFile(rootDir, filename, dispatcherProvider) },
            onTrash = { directFingerprintTrashFile(rootDir, filename, dispatcherProvider) },
        )

    override suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String? =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directReadFile(rootDir, documentId, dispatcherProvider) },
            onTrash = { directReadTrashFile(rootDir, documentId, dispatcherProvider) },
        )

    override fun streamFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): Flow<String> =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directStreamFile(rootDir, documentId, dispatcherProvider) },
            onTrash = { directStreamTrashFile(rootDir, documentId, dispatcherProvider) },
        )

    override suspend fun readFile(uri: Uri): String? = directReadFileUri(uri, dispatcherProvider)

    override suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean,
        uri: Uri?,
    ): String? =
        routeMarkdownDirectory(
            directory = directory,
            onMain = { directSaveFile(rootDir, filename, content, append, dispatcherProvider) },
            onTrash = {
                directSaveTrashFile(rootDir, filename, content, append, dispatcherProvider)
                null
            },
        )

    // Permanent delete always wipes the file with zeros before unlinking. The trash already
    // owns the non-destructive recovery flow; once we reach this path the user has confirmed
    // an irreversible removal so the wipe is a non-configurable application invariant rather
    // than a user setting.
    override suspend fun deleteFileIn(
        directory: MemoDirectoryType,
        filename: String,
        uri: Uri?,
    ) {
        routeMarkdownDirectory(
            directory = directory,
            onMain = {
                directDeleteFile(rootDir = rootDir, filename = filename, dispatcherProvider = dispatcherProvider)
            },
            onTrash = {
                directDeleteTrashFile(rootDir = rootDir, filename = filename, dispatcherProvider = dispatcherProvider)
            },
        )
    }
}
