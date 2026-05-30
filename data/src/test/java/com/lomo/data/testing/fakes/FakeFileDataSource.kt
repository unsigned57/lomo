package com.lomo.data.testing.fakes

import android.net.Uri
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.source.StorageRootType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

class FakeFileDataSource : FileDataSource {
    // In-memory file storage
    // Key is pair of (MemoDirectoryType, Filename), value is file content
    val files = mutableMapOf<Pair<MemoDirectoryType, String>, String>()
    val fileLastModified = mutableMapOf<Pair<MemoDirectoryType, String>, Long>()
    val documentIds = mutableMapOf<Pair<MemoDirectoryType, String>, String>()
    val fileUris = mutableMapOf<Pair<MemoDirectoryType, String>, Uri>()

    // Media and voice
    val savedImages = mutableListOf<Uri>()
    val imageLocations = mutableMapOf<String, String>()
    val deletedImages = mutableListOf<String>()
    val createdVoiceFiles = mutableListOf<String>()
    val deletedVoiceFiles = mutableListOf<String>()

    // Roots
    private val roots = mutableMapOf<StorageRootType, String?>()
    private val rootFlows = StorageRootType.values().associateWith { MutableStateFlow<String?>(null) }

    override suspend fun setRoot(type: StorageRootType, pathOrUri: String) {
        roots[type] = pathOrUri
        rootFlows[type]?.value = pathOrUri
    }

    override fun getRootFlow(type: StorageRootType): Flow<String?> =
        rootFlows[type]?.asStateFlow() ?: MutableStateFlow(null)

    override fun getRootDisplayNameFlow(type: StorageRootType): Flow<String?> =
        rootFlows[type]?.asStateFlow() ?: MutableStateFlow(null)

    override suspend fun createDirectory(name: String): String = name

    var listMetadataInResult: (suspend (MemoDirectoryType) -> List<FileMetadata>)? = null
    val listMetadataInCalls = mutableListOf<MemoDirectoryType>()

    override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> {
        listMetadataInCalls += directory
        return listMetadataInResult?.invoke(directory) ?: files.keys
            .filter { it.first == directory }
            .map { key ->
                val filename = key.second
                FileMetadata(
                    filename = filename,
                    lastModified = fileLastModified[key] ?: 0L,
                    size = files[key]?.length?.toLong()
                )
            }
    }

    override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> {
        return files.keys
            .filter { it.first == directory }
            .map { key ->
                val filename = key.second
                FileMetadataWithId(
                    filename = filename,
                    lastModified = fileLastModified[key] ?: 0L,
                    documentId = documentIds[key] ?: filename,
                    uriString = fileUris[key]?.toString()
                )
            }
    }

    override fun streamMetadataWithIdsIn(directory: MemoDirectoryType): Flow<FileMetadataWithId> =
        flow {
            listMetadataWithIdsIn(directory).forEach { metadata -> emit(metadata) }
        }

    override suspend fun readFileByDocumentIdIn(directory: MemoDirectoryType, documentId: String): String? {
        val key = files.keys.firstOrNull { it.first == directory && documentIds[it] == documentId }
        return key?.let { files[it] }
    }

    val readFileInCalls = mutableListOf<Pair<MemoDirectoryType, String>>()
    var readFileInResult: (suspend (MemoDirectoryType, String) -> String?)? = null

    override suspend fun readFileIn(directory: MemoDirectoryType, filename: String): String? {
        readFileInCalls += Pair(directory, filename)
        return files[Pair(directory, filename)] ?: readFileInResult?.invoke(directory, filename)
    }

    override suspend fun readFile(uri: Uri): String? {
        val key = fileUris.entries.firstOrNull { it.value == uri }?.key
        return key?.let { files[it] }
    }

    override suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean,
        uri: Uri?
    ): String? {
        val key = Pair(directory, filename)
        val existing = files[key] ?: ""
        files[key] = if (append) existing + content else content
        fileLastModified[key] = System.currentTimeMillis()
        if (uri != null) {
            fileUris[key] = uri
        } else if (!fileUris.containsKey(key)) {
            val parsed = runCatching { Uri.parse("file:///$directory/$filename") }.getOrNull()
            if (parsed != null) {
                fileUris[key] = parsed
            }
        }
        if (!documentIds.containsKey(key)) {
            documentIds[key] = filename
        }
        return filename
    }

    val deleteFileInCalls = mutableListOf<Triple<MemoDirectoryType, String, Uri?>>()

    override suspend fun deleteFileIn(directory: MemoDirectoryType, filename: String, uri: Uri?) {
        deleteFileInCalls += Triple(directory, filename, uri)
        val key = Pair(directory, filename)
        files.remove(key)
        fileLastModified.remove(key)
        fileUris.remove(key)
        documentIds.remove(key)
    }

    override suspend fun getFileMetadataIn(directory: MemoDirectoryType, filename: String): FileMetadata? {
        val key = Pair(directory, filename)
        if (!files.containsKey(key)) return null
        return FileMetadata(
            filename = filename,
            lastModified = fileLastModified[key] ?: 0L,
            size = files[key]?.length?.toLong()
        )
    }

    override suspend fun saveImage(uri: Uri): String {
        savedImages += uri
        val filename = uri.lastPathSegment ?: "image_${System.currentTimeMillis()}.png"
        imageLocations[filename] = uri.toString()
        return filename
    }

    override suspend fun listImageFiles(): List<Pair<String, String>> {
        return imageLocations.entries.map { it.key to it.value }
    }

    override suspend fun getImageLocation(filename: String): String? {
        return imageLocations[filename]
    }

    override suspend fun deleteImage(filename: String) {
        deletedImages += filename
        imageLocations.remove(filename)
    }

    override suspend fun createVoiceFile(filename: String): Uri {
        createdVoiceFiles += filename
        return runCatching { Uri.parse("file:///voice/$filename") }.getOrNull() ?: io.mockk.mockk(relaxed = true)
    }

    override suspend fun deleteVoiceFile(filename: String) {
        deletedVoiceFiles += filename
    }
}
