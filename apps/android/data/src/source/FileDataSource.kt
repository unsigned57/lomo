package com.lomo.data.source

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface WorkspaceConfigSource {
    suspend fun setRoot(type: StorageRootType, pathOrUri: String)

    fun getRootFlow(type: StorageRootType): Flow<String?>

    fun getRootDisplayNameFlow(type: StorageRootType): Flow<String?>

    suspend fun createDirectory(name: String): String
}

interface MarkdownStorageDataSource {
    suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata>

    suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId>

    fun streamMetadataWithIdsIn(directory: MemoDirectoryType): Flow<FileMetadataWithId>

    suspend fun readFileByDocumentIdIn(directory: MemoDirectoryType, documentId: String): String?

    fun streamFileByDocumentIdIn(directory: MemoDirectoryType, documentId: String): Flow<String> = flow {
        readFileByDocumentIdIn(directory, documentId)
            ?.run {
                lineSequence().forEach { line -> emit(line) }
            }
    }

    suspend fun readFileIn(directory: MemoDirectoryType, filename: String): String?

    suspend fun fingerprintFileIn(directory: MemoDirectoryType, filename: String): String?

    suspend fun readFile(uri: Uri): String?

    suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean = false,
        uri: Uri? = null,
    ): String?

    suspend fun deleteFileIn(directory: MemoDirectoryType, filename: String, uri: Uri? = null)

    suspend fun getFileMetadataIn(directory: MemoDirectoryType, filename: String): FileMetadata?
}

interface MediaStorageDataSource {
    /** Lists logical image paths under the installed engine workspace and its media selections. */
    suspend fun listImageFiles(selection: MediaStorageSelection): List<Pair<String, String>>
}

interface FileDataSource :
    WorkspaceConfigSource,
    MarkdownStorageDataSource,
    MediaStorageDataSource

data class FileContent(val filename: String, val content: String, val lastModified: Long)

data class FileMetadata(val filename: String, val lastModified: Long, val size: Long? = null)

data class FileMetadataWithId(
    val filename: String,
    val lastModified: Long,
    val documentId: String,
    val uriString: String? = null,
    val size: Long? = null,
)

class FileDataSourceImpl(
    workspaceConfigSource: FileWorkspaceConfigSourceDelegate,
    markdownStorageDataSource: FileMarkdownStorageDataSourceDelegate,
    mediaStorageDataSource: FileMediaStorageDataSourceDelegate,
) : FileDataSource,
    WorkspaceConfigSource by workspaceConfigSource,
    MarkdownStorageDataSource by markdownStorageDataSource,
    MediaStorageDataSource by mediaStorageDataSource
