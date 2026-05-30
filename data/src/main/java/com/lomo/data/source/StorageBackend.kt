package com.lomo.data.source

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface WorkspaceConfigBackend {
    suspend fun createDirectory(name: String): String
}

interface MarkdownStorageBackend {
    suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata>

    suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId>

    fun streamMetadataWithIdsIn(directory: MemoDirectoryType): Flow<FileMetadataWithId>

    suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): FileMetadata?

    suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String?

    suspend fun readFile(uri: Uri): String?

    suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String?

    fun streamFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): Flow<String> =
        flow {
            readFileByDocumentIdIn(directory, documentId)
                ?.lineSequence()
                ?.forEach { line -> emit(line) }
        }

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
}

interface MediaStorageBackend :
    ImageStorageBackend,
    VoiceStorageBackend

interface ImageStorageBackend {
    suspend fun saveImage(
        sourceUri: Uri,
        filename: String,
    )

    suspend fun listImageFiles(): List<Pair<String, String>>

    suspend fun getImageLocation(filename: String): String?

    suspend fun deleteImage(filename: String)
}

interface VoiceStorageBackend {
    suspend fun createVoiceFile(filename: String): Uri

    suspend fun deleteVoiceFile(filename: String)
}
