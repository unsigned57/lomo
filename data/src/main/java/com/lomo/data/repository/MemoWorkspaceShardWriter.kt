package com.lomo.data.repository
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
class MemoWorkspaceShardWriter
constructor(
        private val markdownStorageDataSource: MarkdownStorageDataSource,
        private val fileStateStore: MemoWorkspaceFileStateStore,
    ) {
        suspend fun appendTrashBlock(
            filename: String,
            blockContent: String,
        ) {
            markdownStorageDataSource.saveFileIn(
                directory = MemoDirectoryType.TRASH,
                filename = filename,
                content = blockContent,
                append = true,
            )
            markdownStorageDataSource
                .getFileMetadataIn(MemoDirectoryType.TRASH, filename)
                ?.let { metadata ->
                    fileStateStore.upsertTrashState(filename = filename, lastModified = metadata.lastModified)
                }
        }
        suspend fun appendActiveBlockContent(
            filename: String,
            blockContent: String,
        ) {
            val savedUri =
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = blockContent,
                    append = true,
                    uri = fileStateStore.mainSafUri(filename).toPersistedUriOrNull(),
                )
            fileStateStore.upsertMainState(
                filename = filename,
                lastModified = fileStateStore.resolveMainFileLastModified(filename, savedUri),
                safUri = savedUri,
            )
        }
        suspend fun persistRemovedActiveBlock(
            filename: String,
            removedBlock: RemovedMemoBlock,
        ) {
            if (removedBlock.remainingContent.trim().isEmpty()) {
                deleteShard(directory = MemoDirectoryType.MAIN, filename = filename)
            } else {
                persistMainShard(filename = filename, content = removedBlock.remainingContent)
            }
        }
        suspend fun persistRemovedTrashBlock(
            filename: String,
            removedBlock: RemovedMemoBlock,
        ) {
            if (removedBlock.remainingContent.trim().isEmpty()) {
                deleteShard(directory = MemoDirectoryType.TRASH, filename = filename)
            } else {
                persistShard(
                    directory = MemoDirectoryType.TRASH,
                    filename = filename,
                    content = removedBlock.remainingContent,
                )
            }
        }
        suspend fun persistMainShard(
            filename: String,
            content: String,
        ) {
            val savedUri =
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = content,
                    append = false,
                    uri = fileStateStore.mainSafUri(filename).toPersistedUriOrNull(),
                )
            fileStateStore.resolveSavedMainFileLastModified(filename, savedUri)?.let { lastModified ->
                fileStateStore.upsertMainState(filename = filename, lastModified = lastModified, safUri = savedUri)
            }
        }
        suspend fun persistShard(
            directory: MemoDirectoryType,
            filename: String,
            content: String,
        ) {
            if (directory == MemoDirectoryType.MAIN) {
                persistMainShard(filename = filename, content = content)
                return
            }
            markdownStorageDataSource.saveFileIn(
                directory = directory,
                filename = filename,
                content = content,
                append = false,
            )
            val metadata = markdownStorageDataSource.getFileMetadataIn(directory, filename)
            fileStateStore.upsertTrashState(
                filename = filename,
                lastModified = metadata?.lastModified ?: System.currentTimeMillis(),
            )
        }
        suspend fun deleteShard(
            directory: MemoDirectoryType,
            filename: String,
        ) {
            markdownStorageDataSource.deleteFileIn(
                directory = directory,
                filename = filename,
                uri =
                    when (directory) {
                        MemoDirectoryType.MAIN -> fileStateStore.mainSafUri(filename).toPersistedUriOrNull()
                        MemoDirectoryType.TRASH -> null
                    },
            )
            fileStateStore.deleteState(filename = filename, isTrash = directory == MemoDirectoryType.TRASH)
        }
    }
