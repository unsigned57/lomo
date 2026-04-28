package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileContent
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
    private val fileParseBatchSize: Int = defaultFileParseBatchSize(),
) {
    internal suspend fun parseMainFileContents(
        files: List<FileContent>,
    ): MemoRefreshParseResult {
        val mainMemos = mutableListOf<MemoEntity>()
        val metadata = mutableListOf<LocalFileStateEntity>()
        val datesToReplace = mutableSetOf<String>()

        files.forEach { file ->
            val filename = file.filename.removeSuffix(".md")
            val domainMemos =
                parser.parseContent(
                    content = file.content,
                    filename = filename,
                    fallbackTimestampMillis = file.lastModified,
                )
            val existingMemosByTimestamp =
                dao
                    .getMemosByDate(filename)
                    .groupBy(MemoEntity::timestamp)
            mainMemos.addAll(
                domainMemos.map { memo ->
                    MemoEntity
                        .fromDomain(
                            memo.withStableRefreshId(
                                existingMemosByTimestamp = existingMemosByTimestamp,
                            ),
                        ).copy(updatedAt = file.lastModified)
                },
            )
            metadata.add(
                LocalFileStateEntity(
                    filename = file.filename,
                    isTrash = false,
                    lastKnownModifiedTime = file.lastModified,
                ),
            )
            datesToReplace += filename
        }

        return MemoRefreshParseResult(
            mainMemos = mainMemos,
            trashMemos = emptyList(),
            metadataToUpdate = metadata,
            mainDatesToReplace = datesToReplace,
            trashDatesToReplace = emptySet(),
        )
    }

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

        files.chunked(fileParseBatchSize).forEach { chunk ->
            val existingByDate = preloadExistingMemosByDate(chunk)
            val lookupExisting: suspend (String) -> List<MemoEntity> = { date ->
                existingByDate[date].orEmpty()
            }
            loadParsedResults(chunk) { meta -> parseMainFile(meta, lookupExisting) }.forEach { (memos, meta) ->
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

    private suspend fun preloadExistingMemosByDate(
        chunk: List<FileMetadataWithId>,
    ): Map<String, List<MemoEntity>> {
        val dates = chunk.map { it.filename.removeSuffix(".md") }.distinct()
        if (dates.isEmpty()) return emptyMap()
        return dao.getMemosByDates(dates).groupBy { it.date }
    }

    private suspend fun parseTrashFiles(files: List<FileMetadataWithId>): ParsedRefreshBatch<TrashMemoEntity> {
        val trashMemos = mutableListOf<TrashMemoEntity>()
        val metadata = mutableListOf<LocalFileStateEntity>()
        val datesToReplace = mutableSetOf<String>()

        files.chunked(fileParseBatchSize).forEach { chunk ->
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

    private suspend fun parseMainFile(
        meta: FileMetadataWithId,
        existingByFilename: suspend (String) -> List<MemoEntity> = { dao.getMemosByDate(it) },
    ): Pair<List<MemoEntity>, FileMetadataWithId>? {
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
            existingByFilename(filename).groupBy(MemoEntity::timestamp)
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

private const val MEMO_LOOKUP_BATCH_SIZE = 500
private const val MIN_FILE_PARSE_BATCH_SIZE = 2
private const val MAX_FILE_PARSE_BATCH_SIZE = 8

internal fun defaultFileParseBatchSize(availableProcessors: Int = Runtime.getRuntime().availableProcessors()): Int =
    availableProcessors.coerceIn(MIN_FILE_PARSE_BATCH_SIZE, MAX_FILE_PARSE_BATCH_SIZE)

internal fun Memo.withStableRefreshId(
    existingMemosByTimestamp: Map<Long, List<MemoEntity>>,
): Memo {
    val existingMatches = existingMemosByTimestamp[timestamp].orEmpty()
    return when {
        existingMatches.isEmpty() -> this
        existingMatches.size == 1 -> copy(id = existingMatches.single().id)
        else -> {
            // Multiple existing memos share this timestamp. Use content
            // similarity to pick the best match, preventing ID flip-flop
            // across refresh cycles.
            val contentMatch = existingMatches.firstOrNull { it.content == content }
            if (contentMatch != null) {
                copy(id = contentMatch.id)
            } else {
                // No exact content match; keep the current (new) ID so
                // the caller can reconcile via its own deduplication pass.
                this
            }
        }
    }
}
