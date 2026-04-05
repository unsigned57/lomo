package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.domain.model.Memo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal data class MemoRefreshParseResult(
    val mainMemos: List<MemoEntity>,
    val trashMemos: List<TrashMemoEntity>,
    val metadataToUpdate: List<LocalFileStateEntity>,
    val mainDatesToReplace: Set<String>,
    val trashDatesToReplace: Set<String>,
)

private data class ParsedRefreshBatch<T>(
    val memos: List<T>,
    val metadata: List<LocalFileStateEntity>,
    val datesToReplace: Set<String>,
)

class MemoRefreshParserWorker(
    private val markdownStorageDataSource: MarkdownStorageDataSource,
    private val dao: MemoDao,
    private val parser: MarkdownParser,
) {
    internal suspend fun parse(
        mainFilesToUpdate: List<FileMetadataWithId>,
        trashFilesToUpdate: List<FileMetadataWithId>,
    ): MemoRefreshParseResult {
        val mainBatch = parseMainFiles(mainFilesToUpdate)
        val trashBatch = parseTrashFiles(trashFilesToUpdate)

        return MemoRefreshParseResult(
            mainMemos = mainBatch.memos,
            trashMemos = trashBatch.memos,
            metadataToUpdate = mainBatch.metadata + trashBatch.metadata,
            mainDatesToReplace = mainBatch.datesToReplace,
            trashDatesToReplace = trashBatch.datesToReplace,
        )
    }

    private suspend fun parseMainFiles(files: List<FileMetadataWithId>): ParsedRefreshBatch<MemoEntity> {
        val mainMemos = mutableListOf<MemoEntity>()
        val metadata = mutableListOf<LocalFileStateEntity>()
        val datesToReplace = mutableSetOf<String>()

        files.chunked(FILE_PARSE_BATCH_SIZE).forEach { chunk ->
            loadParsedResults(chunk, ::parseMainFile).forEach { (memos, meta) ->
                datesToReplace.add(meta.filename.removeSuffix(".md"))
                mainMemos.addAll(memos)
                metadata.add(
                    LocalFileStateEntity(
                        filename = meta.filename,
                        isTrash = false,
                        safUri = meta.uriString,
                        lastKnownModifiedTime = meta.lastModified,
                    ),
                )
            }
        }

        return ParsedRefreshBatch(
            memos = mainMemos,
            metadata = metadata,
            datesToReplace = datesToReplace,
        )
    }

    private suspend fun parseTrashFiles(files: List<FileMetadataWithId>): ParsedRefreshBatch<TrashMemoEntity> {
        val trashMemos = mutableListOf<TrashMemoEntity>()
        val metadata = mutableListOf<LocalFileStateEntity>()
        val datesToReplace = mutableSetOf<String>()

        files.chunked(FILE_PARSE_BATCH_SIZE).forEach { chunk ->
            val chunkResults = loadParsedResults(chunk, ::parseTrashFile)
            val activeMemoIdsInDb = resolveActiveMemoIds(chunkResults)

            chunkResults.forEach { (memos, meta) ->
                datesToReplace.add(meta.filename.removeSuffix(".md"))
                trashMemos.addAll(memos.filter { it.id !in activeMemoIdsInDb })
                metadata.add(
                    LocalFileStateEntity(
                        filename = meta.filename,
                        isTrash = true,
                        lastKnownModifiedTime = meta.lastModified,
                    ),
                )
            }
        }

        return ParsedRefreshBatch(
            memos = trashMemos,
            metadata = metadata,
            datesToReplace = datesToReplace,
        )
    }

    private suspend fun <T> loadParsedResults(
        chunk: List<FileMetadataWithId>,
        parserBlock: suspend (FileMetadataWithId) -> Pair<List<T>, FileMetadataWithId>?,
    ): List<Pair<List<T>, FileMetadataWithId>> =
        coroutineScope {
            chunk.map { meta ->
                async(Dispatchers.Default) {
                    parserBlock(meta)
                }
            }.awaitAll()
        }.filterNotNull()

    private suspend fun resolveActiveMemoIds(
        chunkResults: List<Pair<List<TrashMemoEntity>, FileMetadataWithId>>,
    ): Set<String> {
        val trashMemoIdsInChunk =
            chunkResults
                .flatMap { (memos, _) -> memos.map { it.id } }
                .distinct()
        return if (trashMemoIdsInChunk.isNotEmpty()) {
            trashMemoIdsInChunk
                .chunked(MEMO_LOOKUP_BATCH_SIZE)
                .flatMap { ids -> dao.getMemosByIds(ids) }
                .asSequence()
                .map { it.id }
                .toSet()
        } else {
            emptySet()
        }
    }

    private suspend fun parseMainFile(meta: FileMetadataWithId): Pair<List<MemoEntity>, FileMetadataWithId>? {
        val content =
            withContext(Dispatchers.IO) {
                markdownStorageDataSource.readFileByDocumentIdIn(
                    MemoDirectoryType.MAIN,
                    meta.documentId,
                )
            }
        if (content == null) return null

        val filename = meta.filename.removeSuffix(".md")
        val domainMemos =
            parser.parseContent(
                content = content,
                filename = filename,
                fallbackTimestampMillis = meta.lastModified,
            )
        val existingMemosByTimestamp =
            dao
                .getMemosByDate(filename)
                .groupBy(MemoEntity::timestamp)
        val memos =
            domainMemos.map { memo ->
                MemoEntity
                    .fromDomain(
                        memo.withStableRefreshId(
                            existingMemosByTimestamp = existingMemosByTimestamp,
                        ),
                    ).copy(updatedAt = meta.lastModified)
            }
        return memos to meta
    }

    private suspend fun parseTrashFile(meta: FileMetadataWithId): Pair<List<TrashMemoEntity>, FileMetadataWithId>? {
        val content =
            withContext(Dispatchers.IO) {
                markdownStorageDataSource.readFileByDocumentIdIn(
                    MemoDirectoryType.TRASH,
                    meta.documentId,
                )
            }
        if (content == null) return null

        val filename = meta.filename.removeSuffix(".md")
        val domainMemos =
            parser.parseContent(
                content = content,
                filename = filename,
                fallbackTimestampMillis = meta.lastModified,
            )
        val memos =
            domainMemos.map { memo ->
                TrashMemoEntity
                    .fromDomain(memo.copy(isDeleted = true))
                    .copy(updatedAt = meta.lastModified)
            }
        return memos to meta
    }
}

private const val FILE_PARSE_BATCH_SIZE = 10
private const val MEMO_LOOKUP_BATCH_SIZE = 500

private fun Memo.withStableRefreshId(
    existingMemosByTimestamp: Map<Long, List<MemoEntity>>,
): Memo {
    val existingMatches = existingMemosByTimestamp[timestamp].orEmpty()
    return if (existingMatches.size == 1) {
        copy(id = existingMatches.single().id)
    } else {
        this
    }
}
