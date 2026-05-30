package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.source.FileContent
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MemoDirectoryType
import com.lomo.domain.model.Memo
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

private data class ParsedRefreshBatch<T>(
    val memos: List<T>,
    val metadata: List<LocalFileStateEntity>,
    val datesToReplace: Set<String>,
)

class MemoRefreshParserWorker(
    private val workspaceProjector: MemoWorkspaceProjector,
    private val dao: MemoDao,
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
            val existingMemosByTimestamp =
                dao
                    .getMemosByDate(filename)
                    .groupBy(MemoEntity::timestamp)
            val changeSet =
                workspaceProjector.projectMainFileContent(
                    file = file,
                    existingActiveMemos = existingMemosByTimestamp.values.flatten(),
                )
            mainMemos.addAll(changeSet.memos)
            metadata.add(changeSet.metadata)
            datesToReplace += changeSet.dateKey
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
            loadParsedResults(chunk) { meta -> parseMainFile(meta, lookupExisting) }.forEach { changeSet ->
                datesToReplace.add(changeSet.dateKey)
                mainMemos.addAll(changeSet.memos)
                metadata.add(changeSet.metadata)
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

            chunkResults.forEach { changeSet ->
                datesToReplace.add(changeSet.dateKey)
                trashMemos.addAll(changeSet.memos.filter { it.id !in activeMemoIdsInDb })
                metadata.add(changeSet.metadata)
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
        parserBlock: suspend (FileMetadataWithId) -> T?,
    ): List<T> =
        coroutineScope {
            chunk.map { meta ->
                async(Dispatchers.Default) {
                    parserBlock(meta)
                }
            }.awaitAll()
        }.filterNotNull()

    private suspend fun resolveActiveMemoIds(
        chunkResults: List<MemoProjectionChangeSet.Trash>,
    ): Set<String> {
        val trashMemoIdsInChunk =
            chunkResults
                .flatMap { changeSet -> changeSet.memos.map { it.id } }
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
    ): MemoProjectionChangeSet.Active? {
        val filename = meta.filename.removeSuffix(".md")
        return workspaceProjector.projectShard(
            directory = MemoDirectoryType.MAIN,
            metadata = meta,
            existingActiveMemos = existingByFilename(filename),
        ) as? MemoProjectionChangeSet.Active
    }

    private suspend fun parseTrashFile(meta: FileMetadataWithId): MemoProjectionChangeSet.Trash? =
        when (
            val changeSet =
                workspaceProjector.projectShard(
                    directory = MemoDirectoryType.TRASH,
                    metadata = meta,
                )
        ) {
            is MemoProjectionChangeSet.Trash -> changeSet
            null -> null
            is MemoProjectionChangeSet.Active ->
                error("Trash projection returned active change set for ${meta.filename}")
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
