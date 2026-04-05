package com.lomo.data.repository

import com.lomo.domain.model.Memo

internal class TrashMemoMutationDelegate(
    private val runtime: MemoMutationRuntime,
) : MemoTrashMutationOperations {
    override suspend fun deleteMemo(memo: Memo) {
        runtime.trashMutationHandler.moveToTrash(memo)
    }

    override suspend fun deleteMemoInDb(memo: Memo): Long? {
        val sourceMemo = runtime.daoBundle.memoDao.getMemo(memo.id)?.toDomain() ?: return null
        val outboxId =
            moveMemoToTrashWithOutbox(
                daoBundle = runtime.daoBundle,
                sourceMemo = sourceMemo,
                outbox = buildDeleteOutbox(sourceMemo),
            )
        runtime.memoVersionRecorder.enqueueLocalRevision(
            memo = sourceMemo.copy(isDeleted = true),
            lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.TRASHED,
            origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_TRASH,
        )
        return outboxId
    }

    override suspend fun flushDeleteMemoToFile(memo: Memo): Boolean =
        runtime.trashMutationHandler.moveToTrashFileOnly(memo).also { moved ->
            if (moved) {
                recordMainMemoStateAfterTrashMove(runtime, memo)
            }
        }

    override suspend fun restoreMemo(memo: Memo) {
        runtime.trashMutationHandler.restoreFromTrash(memo)
    }

    override suspend fun restoreMemoInDb(memo: Memo): Long? {
        val sourceMemo = runtime.daoBundle.memoTrashDao.getTrashMemo(memo.id)?.toDomain() ?: return null
        val outboxId =
            restoreMemoFromTrashWithOutbox(
                daoBundle = runtime.daoBundle,
                sourceMemo = sourceMemo,
                outbox = buildRestoreOutbox(sourceMemo),
            )
        runtime.memoVersionRecorder.enqueueLocalRevision(
            memo = sourceMemo.copy(isDeleted = false),
            lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.ACTIVE,
            origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_RESTORE,
        )
        return outboxId
    }

    override suspend fun flushRestoreMemoToFile(memo: Memo): Boolean =
        runtime.trashMutationHandler.restoreFromTrashFileOnly(memo).also { restored ->
            if (restored) {
                runtime.s3LocalChangeRecorder.recordMemoUpsert("${memo.dateKey}.md")
            }
        }

    override suspend fun deletePermanently(memo: Memo) {
        runtime.trashMutationHandler.deleteFromTrashPermanently(memo)
    }

    override suspend fun clearTrash() {
        clearTrashPermanently(runtime.trashMutationHandler, runtime.daoBundle.memoTrashDao)
    }
}

private suspend fun recordMainMemoStateAfterTrashMove(
    runtime: MemoMutationRuntime,
    memo: Memo,
) {
    val filename = "${memo.dateKey}.md"
    val mainState = runtime.localFileStateDao.getByFilename(filename, false)
    if (mainState != null) {
        runtime.s3LocalChangeRecorder.recordMemoUpsert(filename)
    } else {
        runtime.s3LocalChangeRecorder.recordMemoDelete(filename)
    }
}

private suspend fun clearTrashPermanently(
    trashMutationHandler: MemoTrashMutationHandler,
    memoTrashDao: com.lomo.data.local.dao.MemoTrashDao,
) {
    val trashMemos = memoTrashDao.getDeletedMemos()
    if (trashMemos.isEmpty()) {
        return
    }
    trashMemos.forEach { trashMemo ->
        trashMutationHandler.deleteFromTrashPermanently(trashMemo.toDomain())
    }
}
