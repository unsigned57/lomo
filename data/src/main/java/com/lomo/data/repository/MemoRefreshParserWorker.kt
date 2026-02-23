package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MemoDirectoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal data class MemoRefreshParseResult(
    val mainMemos: List<MemoEntity>,
    val trashMemos: List<TrashMemoEntity>,
    val metadataToUpdate: List<LocalFileStateEntity>,
    val mainDatesToReplace: Set<String>,
    val trashDatesToReplace: Set<String>,
)

internal class MemoRefreshParserWorker(
    private val fileDataSource: FileDataSource,
    private val dao: MemoDao,
    private val parser: MarkdownParser,
) {
    suspend fun parse(
        mainFilesToUpdate: List<FileMetadataWithId>,
        trashFilesToUpdate: List<FileMetadataWithId>,
    ): MemoRefreshParseResult {
        val mainMemos = mutableListOf<MemoEntity>()
        val trashMemos = mutableListOf<TrashMemoEntity>()
        val metadataToUpdate = mutableListOf<LocalFileStateEntity>()
        val mainDatesToReplace = mutableSetOf<String>()
        val trashDatesToReplace = mutableSetOf<String>()

        mainFilesToUpdate.chunked(10).forEach { chunk ->
            val chunkResults =
                coroutineScope {
                    chunk
                        .map { meta ->
                            async(Dispatchers.Default) {
                                parseMainFile(meta)
                            }
                        }.awaitAll()
                }

            chunkResults.filterNotNull().forEach { (memos, meta) ->
                val dateStr = meta.filename.removeSuffix(".md")
                mainDatesToReplace.add(dateStr)
                mainMemos.addAll(memos)
                metadataToUpdate.add(
                    LocalFileStateEntity(
                        filename = meta.filename,
                        isTrash = false,
                        safUri = meta.uriString,
                        lastKnownModifiedTime = meta.lastModified,
                    ),
                )
            }
        }

        trashFilesToUpdate.chunked(10).forEach { chunk ->
            val chunkResults =
                coroutineScope {
                    chunk
                        .map { meta ->
                            async(Dispatchers.Default) {
                                parseTrashFile(meta)
                            }
                        }.awaitAll()
                }.filterNotNull()

            val trashMemoIdsInChunk =
                chunkResults
                    .flatMap { (memos, _) ->
                        memos.map { it.id }
                    }.distinct()
            val activeMemoIdsInDb =
                if (trashMemoIdsInChunk.isNotEmpty()) {
                    trashMemoIdsInChunk
                        .chunked(500)
                        .flatMap { ids ->
                            dao.getMemosByIds(ids)
                        }.asSequence()
                        .map { it.id }
                        .toSet()
                } else {
                    emptySet()
                }

            chunkResults.forEach { (memos, meta) ->
                val dateStr = meta.filename.removeSuffix(".md")
                val filteredMemos = memos.filter { it.id !in activeMemoIdsInDb }
                trashDatesToReplace.add(dateStr)
                trashMemos.addAll(filteredMemos)
                metadataToUpdate.add(
                    LocalFileStateEntity(
                        filename = meta.filename,
                        isTrash = true,
                        lastKnownModifiedTime = meta.lastModified,
                    ),
                )
            }
        }

        return MemoRefreshParseResult(
            mainMemos = mainMemos,
            trashMemos = trashMemos,
            metadataToUpdate = metadataToUpdate,
            mainDatesToReplace = mainDatesToReplace,
            trashDatesToReplace = trashDatesToReplace,
        )
    }

    private suspend fun parseMainFile(meta: FileMetadataWithId): Pair<List<MemoEntity>, FileMetadataWithId>? {
        val content =
            fileDataSource.readFileByDocumentIdIn(
                MemoDirectoryType.MAIN,
                meta.documentId,
            )
        if (content == null) return null

        val filename = meta.filename.removeSuffix(".md")
        val domainMemos =
            parser.parseContent(
                content = content,
                filename = filename,
                fallbackTimestampMillis = meta.lastModified,
            )
        return domainMemos.map { MemoEntity.fromDomain(it) } to meta
    }

    private suspend fun parseTrashFile(meta: FileMetadataWithId): Pair<List<TrashMemoEntity>, FileMetadataWithId>? {
        val content =
            fileDataSource.readFileByDocumentIdIn(
                MemoDirectoryType.TRASH,
                meta.documentId,
            )
        if (content == null) return null

        val filename = meta.filename.removeSuffix(".md")
        val domainMemos =
            parser.parseContent(
                content = content,
                filename = filename,
                fallbackTimestampMillis = meta.lastModified,
            )
        val memos =
            domainMemos.map {
                TrashMemoEntity.fromDomain(
                    it.copy(isDeleted = true),
                )
            }
        return memos to meta
    }
}
