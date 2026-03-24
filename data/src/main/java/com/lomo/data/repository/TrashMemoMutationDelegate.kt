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
        return moveMemoToTrashWithOutbox(
            daoBundle = runtime.daoBundle,
            sourceMemo = sourceMemo,
            outbox = buildDeleteOutbox(sourceMemo),
        )
    }

    override suspend fun flushDeleteMemoToFile(memo: Memo): Boolean =
        runtime.trashMutationHandler.moveToTrashFileOnly(memo)

    override suspend fun restoreMemo(memo: Memo) {
        runtime.trashMutationHandler.restoreFromTrash(memo)
    }

    override suspend fun restoreMemoInDb(memo: Memo): Long? {
        val sourceMemo = runtime.daoBundle.memoTrashDao.getTrashMemo(memo.id)?.toDomain() ?: return null
        return restoreMemoFromTrashWithOutbox(
            daoBundle = runtime.daoBundle,
            sourceMemo = sourceMemo,
            outbox = buildRestoreOutbox(sourceMemo),
        )
    }

    override suspend fun flushRestoreMemoToFile(memo: Memo): Boolean =
        runtime.trashMutationHandler.restoreFromTrashFileOnly(memo)

    override suspend fun deletePermanently(memo: Memo) {
        runtime.trashMutationHandler.deleteFromTrashPermanently(memo)
    }
}
