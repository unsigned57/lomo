package com.lomo.data.repository

import com.lomo.data.source.FileContent
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class MemoWorkspaceReader
    @Inject
    constructor(
        private val markdownStorageDataSource: MarkdownStorageDataSource,
        private val fileStateStore: MemoWorkspaceFileStateStore,
    ) {
        fun streamShardMetadata(directory: MemoDirectoryType): Flow<FileMetadataWithId> =
            markdownStorageDataSource.streamMetadataWithIdsIn(directory)

        suspend fun readShardFileContent(
            directory: MemoDirectoryType,
            filename: String,
        ): FileContent? {
            val metadata = markdownStorageDataSource.getFileMetadataIn(directory, filename) ?: return null
            val content = readShardContentForMutation(directory = directory, filename = filename) ?: return null
            return FileContent(
                filename = filename,
                content = content,
                lastModified = metadata.lastModified,
            )
        }

        suspend fun readActiveShardContent(filename: String): String? {
            val cachedUri = fileStateStore.mainSafUri(filename).toPersistedUriOrNull()
            return if (cachedUri != null) {
                markdownStorageDataSource.readFile(cachedUri)
                    ?: markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
            } else {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
            }
        }

        suspend fun readTrashShardContent(filename: String): String? =
            markdownStorageDataSource.readFileIn(MemoDirectoryType.TRASH, filename)

        fun streamShardContentByDocumentId(
            directory: MemoDirectoryType,
            documentId: String,
        ): Flow<String> =
            markdownStorageDataSource.streamFileByDocumentIdIn(
                directory = directory,
                documentId = documentId,
            )

        suspend fun readShardContentForMutation(
            directory: MemoDirectoryType,
            filename: String,
        ): String? =
            when (directory) {
                MemoDirectoryType.MAIN -> readActiveShardContent(filename)
                MemoDirectoryType.TRASH -> readTrashShardContent(filename)
            }
    }
