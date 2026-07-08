package com.lomo.data.repository
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.projection.MemoProjectionProjector
import com.lomo.data.parser.MarkdownMemoDocument
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileContent
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MemoDirectoryType
class MemoWorkspaceProjector
constructor(
        private val reader: MemoWorkspaceReader,
        private val parser: MarkdownParser,
    ) {
        suspend fun projectShard(
            directory: MemoDirectoryType,
            metadata: FileMetadataWithId,
            existingActiveMemos: List<MemoEntity> = emptyList(),
        ): MemoProjectionChangeSet? {
            val document =
                parser.parseDocumentLines(
                    lines =
                        reader.streamShardContentByDocumentId(
                            directory = directory,
                            documentId = metadata.documentId,
                        ),
                    filename = metadata.filename.removeSuffix(".md"),
                    fallbackTimestampMillis = metadata.lastModified,
                )
            if (document.blocks.isEmpty()) {
                return null
            }
            return projectDocument(
                directory = directory,
                filename = metadata.filename,
                document = document,
                lastModified = metadata.lastModified,
                safUri = metadata.uriString,
                existingActiveMemos = existingActiveMemos,
            )
        }
        fun projectMainFileContent(
            file: FileContent,
            existingActiveMemos: List<MemoEntity>,
        ): MemoProjectionChangeSet.Active =
            projectShardContent(
                directory = MemoDirectoryType.MAIN,
                filename = file.filename,
                content = file.content,
                lastModified = file.lastModified,
                safUri = null,
                existingActiveMemos = existingActiveMemos,
            ) as MemoProjectionChangeSet.Active
        private fun projectShardContent(
            directory: MemoDirectoryType,
            filename: String,
            content: String,
            lastModified: Long,
            safUri: String?,
            existingActiveMemos: List<MemoEntity>,
        ): MemoProjectionChangeSet {
            val document =
                parser.parseDocument(
                    content = content,
                    filename = filename.removeSuffix(".md"),
                    fallbackTimestampMillis = lastModified,
                )
            return projectDocument(
                directory = directory,
                filename = filename,
                document = document,
                lastModified = lastModified,
                safUri = safUri,
                existingActiveMemos = existingActiveMemos,
            )
        }
        private fun projectDocument(
            directory: MemoDirectoryType,
            filename: String,
            document: MarkdownMemoDocument,
            lastModified: Long,
            safUri: String?,
            existingActiveMemos: List<MemoEntity>,
        ): MemoProjectionChangeSet {
            val dateKey = filename.removeSuffix(".md")
            val domainMemos = document.blocks.map { block -> block.memo }
            return when (directory) {
                MemoDirectoryType.MAIN ->
                    MemoProjectionChangeSet.Active(
                        memos =
                            domainMemos.map { memo ->
                                MemoProjectionProjector
                                    .projectActive(
                                        memo
                                            .withStableRefreshId(
                                                existingMemosByTimestamp =
                                                    existingActiveMemos.groupBy(MemoEntity::timestamp),
                                            ).copy(updatedAt = lastModified),
                                    ).entity
                            },
                        metadata =
                            LocalFileStateEntity(
                                filename = filename,
                                isTrash = false,
                                safUri = safUri,
                                lastKnownModifiedTime = lastModified,
                            ),
                        dateKey = dateKey,
                    )
                MemoDirectoryType.TRASH ->
                    MemoProjectionChangeSet.Trash(
                        memos =
                            domainMemos.map { memo ->
                                MemoProjectionProjector
                                    .projectTrash(memo.copy(isDeleted = true, updatedAt = lastModified))
                                    .entity
                            },
                        metadata =
                            LocalFileStateEntity(
                                filename = filename,
                                isTrash = true,
                                lastKnownModifiedTime = lastModified,
                            ),
                        dateKey = dateKey,
                    )
            }
        }
    }
