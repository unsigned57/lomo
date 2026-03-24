package com.lomo.data.repository

import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.domain.model.Memo

internal suspend fun persistMemoWithOutbox(
    daoBundle: MemoMutationDaoBundle,
    memo: MemoEntity,
    outbox: MemoFileOutboxEntity,
): Long {
    var outboxId = 0L
    daoBundle.runInTransaction {
        persistMainMemoEntity(daoBundle, memo)
        outboxId = daoBundle.memoOutboxDao.insertMemoFileOutbox(outbox)
    }
    return outboxId
}

internal suspend fun moveMemoToTrashWithOutbox(
    daoBundle: MemoMutationDaoBundle,
    sourceMemo: Memo,
    outbox: MemoFileOutboxEntity,
): Long {
    var outboxId = 0L
    daoBundle.runInTransaction {
        daoBundle.memoWriteDao.deleteMemoById(sourceMemo.id)
        daoBundle.memoTagDao.deleteTagRefsByMemoId(sourceMemo.id)
        daoBundle.memoFtsDao.deleteMemoFts(sourceMemo.id)
        daoBundle.memoTrashDao.insertTrashMemo(TrashMemoEntity.fromDomain(sourceMemo.copy(isDeleted = true)))
        outboxId = daoBundle.memoOutboxDao.insertMemoFileOutbox(outbox)
    }
    return outboxId
}

internal suspend fun restoreMemoFromTrashWithOutbox(
    daoBundle: MemoMutationDaoBundle,
    sourceMemo: Memo,
    outbox: MemoFileOutboxEntity,
): Long {
    var outboxId = 0L
    daoBundle.runInTransaction {
        persistMainMemoEntity(daoBundle, MemoEntity.fromDomain(sourceMemo.copy(isDeleted = false)))
        daoBundle.memoTrashDao.deleteTrashMemoById(sourceMemo.id)
        outboxId = daoBundle.memoOutboxDao.insertMemoFileOutbox(outbox)
    }
    return outboxId
}
