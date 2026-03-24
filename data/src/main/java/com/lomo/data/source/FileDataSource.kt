package com.lomo.data.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

interface WorkspaceConfigSource {
    suspend fun setRoot(
        type: StorageRootType,
        pathOrUri: String,
    )

    fun getRootFlow(type: StorageRootType): Flow<String?>

    fun getRootDisplayNameFlow(type: StorageRootType): Flow<String?>

    suspend fun createDirectory(name: String): String
}

interface MarkdownStorageDataSource {
    suspend fun listFilesIn(
        directory: MemoDirectoryType,
        targetFilename: String? = null,
    ): List<FileContent>

    suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata>

    suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId>

    suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String?

    suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String?

    suspend fun readFile(uri: Uri): String?

    suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean = false,
        uri: Uri? = null,
    ): String?

    suspend fun deleteFileIn(
        directory: MemoDirectoryType,
        filename: String,
        uri: Uri? = null,
    )

    suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): FileMetadata?
}

interface MediaStorageDataSource {
    suspend fun saveImage(uri: Uri): String

    suspend fun listImageFiles(): List<Pair<String, String>>

    suspend fun getImageLocation(filename: String): String?

    suspend fun deleteImage(filename: String)

    suspend fun createVoiceFile(filename: String): Uri

    suspend fun deleteVoiceFile(filename: String)
}

interface FileDataSource :
    WorkspaceConfigSource,
    MarkdownStorageDataSource,
    MediaStorageDataSource

data class FileContent(
    val filename: String,
    val content: String,
    val lastModified: Long,
)

data class FileMetadata(
    val filename: String,
    val lastModified: Long,
)

data class FileMetadataWithId(
    val filename: String,
    val lastModified: Long,
    val documentId: String,
    val uriString: String? = null,
)

class FileDataSourceImpl
    @Inject
    constructor(
        workspaceConfigSource: FileWorkspaceConfigSourceDelegate,
        markdownStorageDataSource: FileMarkdownStorageDataSourceDelegate,
        mediaStorageDataSource: FileMediaStorageDataSourceDelegate,
    ) : FileDataSource,
        WorkspaceConfigSource by workspaceConfigSource,
        MarkdownStorageDataSource by markdownStorageDataSource,
        MediaStorageDataSource by mediaStorageDataSource
