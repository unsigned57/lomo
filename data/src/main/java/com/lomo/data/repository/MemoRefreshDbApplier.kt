package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoFtsDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.util.SearchTokenizer
import com.lomo.domain.model.MemoRevisionOrigin
import com.lomo.domain.model.MemoRevisionLifecycleState

class MemoRefreshDbApplier(
    private val memoDao: MemoDao,
    private val memoWriteDao: MemoWriteDao,
    private val memoTagDao: MemoTagDao,
    private val memoFtsDao: MemoFtsDao,
    private val memoTrashDao: MemoTrashDao,
    private val localFileStateDao: LocalFileStateDao,
    private val memoVersionJournal: MemoVersionJournal,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit,
) {
    internal suspend fun apply(
        parseResult: MemoRefreshParseResult,
        filesToDeleteInDb: Set<Pair<String, Boolean>>,
        origin: MemoRevisionOrigin = MemoRevisionOrigin.IMPORT_REFRESH,
    ) {
        if (affectedDateKeys(parseResult, filesToDeleteInDb).isEmpty()) {
            return
        }
        val previousStates =
            loadAffectedStates(
                parseResult = parseResult,
                filesToDeleteInDb = filesToDeleteInDb,
                memoDao = memoDao,
                memoTrashDao = memoTrashDao,
            )

        runInTransaction {
            applyInternal(
                parseResult = parseResult,
                filesToDeleteInDb = filesToDeleteInDb,
                previousStates = previousStates,
                origin = origin,
            )
        }
    }

    private suspend fun applyInternal(
        parseResult: MemoRefreshParseResult,
        filesToDeleteInDb: Set<Pair<String, Boolean>>,
        previousStates: Map<String, RefreshMemoState>,
        origin: MemoRevisionOrigin,
    ) {
        replaceDates(parseResult)

        val deduplicatedMainMemos = deduplicateMemos(parseResult.mainMemos)
        val filteredTrashMemos = filterTrashMemos(parseResult.trashMemos, deduplicatedMainMemos)
        val mainIds = deduplicatedMainMemos.map { it.id }.toSet()

        deleteConflictingTrash(mainIds)
        persistMainMemos(deduplicatedMainMemos)
        persistTrashMemos(filteredTrashMemos)
        upsertMetadata(parseResult.metadataToUpdate)
        filesToDeleteInDb.forEach { (filename, isTrash) ->
            deleteFileRecords(filename, isTrash)
        }
        memoVersionJournal.appendImportedRefreshRevisions(
            changes =
                buildImportedChanges(
                    previousStates = previousStates,
                    currentStates = buildCurrentStates(deduplicatedMainMemos, filteredTrashMemos),
                ),
            origin = origin,
        )
    }

    private suspend fun replaceDates(parseResult: MemoRefreshParseResult) {
        parseResult.mainDatesToReplace.forEach { date ->
            deleteMainDate(date)
        }
        parseResult.trashDatesToReplace.forEach { date ->
            memoTrashDao.deleteTrashMemosByDate(date)
        }
    }

    private suspend fun deleteMainDate(date: String) {
        val memoIds = memoDao.getMemosByDate(date).map { it.id }
        if (memoIds.isNotEmpty()) {
            memoTagDao.deleteTagRefsByMemoIds(memoIds)
            memoFtsDao.deleteMemoFtsByIds(memoIds)
        }
        memoWriteDao.deleteMemosByDate(date)
    }

    private suspend fun deleteConflictingTrash(mainIds: Set<String>) {
        if (mainIds.isNotEmpty()) {
            memoTrashDao.deleteTrashMemosByIds(mainIds.toList())
        }
    }

    private suspend fun persistMainMemos(memos: List<MemoEntity>) {
        if (memos.isEmpty()) {
            return
        }

        memoWriteDao.insertMemos(memos)
        memoTagDao.replaceTagRefsForMemos(memos)
        memoFtsDao.replaceMemoFtsBatch(
            memos.map {
                MemoFtsEntity(
                    memoId = it.id,
                    content = SearchTokenizer.tokenize(it.content),
                )
            },
        )
    }

    private suspend fun persistTrashMemos(memos: List<TrashMemoEntity>) {
        if (memos.isNotEmpty()) {
            memoTrashDao.insertTrashMemos(memos)
        }
    }

    private suspend fun upsertMetadata(metadata: List<LocalFileStateEntity>) {
        if (metadata.isNotEmpty()) {
            localFileStateDao.upsertAll(metadata)
        }
    }

    private suspend fun deleteFileRecords(
        filename: String,
        isTrash: Boolean,
    ) {
        val date = filename.removeSuffix(".md")
        if (isTrash) {
            deleteTrashDate(date)
        } else {
            deleteMainDateEntries(date)
        }
        localFileStateDao.deleteByFilename(filename, isTrash)
    }

    private suspend fun deleteTrashDate(date: String) {
        val trashMemoIds = memoTrashDao.getTrashMemosByDate(date).map { it.id }
        if (trashMemoIds.isNotEmpty()) {
            memoTrashDao.deleteTrashMemosByIds(trashMemoIds)
        }
    }

    private suspend fun deleteMainDateEntries(date: String) {
        val memoIds = memoDao.getMemosByDate(date).map { it.id }
        if (memoIds.isNotEmpty()) {
            memoTagDao.deleteTagRefsByMemoIds(memoIds)
            memoWriteDao.deleteMemosByIds(memoIds)
            memoFtsDao.deleteMemoFtsByIds(memoIds)
        }
    }
}

private fun affectedDateKeys(
    parseResult: MemoRefreshParseResult,
    filesToDeleteInDb: Set<Pair<String, Boolean>>,
): Set<String> =
    buildSet {
        addAll(parseResult.mainDatesToReplace)
        addAll(parseResult.trashDatesToReplace)
        filesToDeleteInDb.forEach { (filename, _) ->
            add(filename.removeSuffix(".md"))
        }
    }

private fun deduplicateMemos(memos: List<MemoEntity>): List<MemoEntity> =
    memos.associateBy { it.id }.values.toList()

private fun filterTrashMemos(
    trashMemos: List<TrashMemoEntity>,
    mainMemos: List<MemoEntity>,
): List<TrashMemoEntity> {
    val mainIds = mainMemos.map { it.id }.toSet()
    return trashMemos
        .associateBy { it.id }
        .values
        .filter { trashMemo -> trashMemo.id !in mainIds }
        .toList()
}

private data class RefreshMemoState(
    val memo: com.lomo.domain.model.Memo,
    val lifecycleState: MemoRevisionLifecycleState,
)

private suspend fun loadAffectedStates(
    parseResult: MemoRefreshParseResult,
    filesToDeleteInDb: Set<Pair<String, Boolean>>,
    memoDao: MemoDao,
    memoTrashDao: MemoTrashDao,
): Map<String, RefreshMemoState> {
    val affectedDates =
        buildSet {
            addAll(parseResult.mainDatesToReplace)
            addAll(parseResult.trashDatesToReplace)
            filesToDeleteInDb.forEach { (filename, _) -> add(filename.removeSuffix(".md")) }
        }
    if (affectedDates.isEmpty()) {
        return emptyMap()
    }

    val states = linkedMapOf<String, RefreshMemoState>()
    affectedDates.forEach { dateKey ->
        memoDao.getMemosByDate(dateKey).forEach { entity ->
            states[entity.id] = RefreshMemoState(entity.toDomain(), MemoRevisionLifecycleState.ACTIVE)
        }
        memoTrashDao.getTrashMemosByDate(dateKey).forEach { entity ->
            states.putIfAbsent(
                entity.id,
                RefreshMemoState(entity.toDomain(), MemoRevisionLifecycleState.TRASHED),
            )
        }
    }
    return states
}

private fun buildCurrentStates(
    mainMemos: List<MemoEntity>,
    trashMemos: List<TrashMemoEntity>,
): Map<String, RefreshMemoState> {
    val states = linkedMapOf<String, RefreshMemoState>()
    mainMemos.forEach { entity ->
        states[entity.id] = RefreshMemoState(entity.toDomain(), MemoRevisionLifecycleState.ACTIVE)
    }
    trashMemos.forEach { entity ->
        states.putIfAbsent(
            entity.id,
            RefreshMemoState(entity.toDomain(), MemoRevisionLifecycleState.TRASHED),
        )
    }
    return states
}

private fun buildImportedChanges(
    previousStates: Map<String, RefreshMemoState>,
    currentStates: Map<String, RefreshMemoState>,
): List<ImportedMemoRevisionChange> {
    val changes = mutableListOf<ImportedMemoRevisionChange>()
    currentStates.forEach { (memoId, currentState) ->
        val previousState = previousStates[memoId]
        if (
            previousState == null ||
            previousState.lifecycleState != currentState.lifecycleState ||
            previousState.memo.rawContent != currentState.memo.rawContent
        ) {
            changes +=
                ImportedMemoRevisionChange.Upsert(
                    memo = currentState.memo,
                    lifecycleState = currentState.lifecycleState,
                )
        }
    }
    previousStates.forEach { (memoId, previousState) ->
        if (memoId !in currentStates) {
            changes +=
                ImportedMemoRevisionChange.Delete(
                    memoId = previousState.memo.id,
                    dateKey = previousState.memo.dateKey,
                    rawContent = previousState.memo.rawContent,
                    content = previousState.memo.content,
                    timestamp = previousState.memo.timestamp,
                    updatedAt = previousState.memo.updatedAt,
                )
        }
    }
    return changes
}
