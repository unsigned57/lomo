package com.lomo.data.repository

import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.projection.ActiveMemoProjection
import com.lomo.data.local.projection.MemoProjectionProjector
import com.lomo.domain.model.MemoRevisionLifecycleState

internal suspend fun persistMemoWithOutbox(
    daoBundle: MemoMutationDaoBundle,
    memoProjection: ActiveMemoProjection,
    outbox: MemoFileOutboxEntity,
): Long {
    var outboxId = 0L
    daoBundle.runInTransaction {
        persistMainMemoProjection(daoBundle, memoProjection)
        outboxId = daoBundle.memoOutboxDao.insertMemoFileOutbox(outbox)
    }
    return outboxId
}

internal suspend fun moveMemoToTrashWithOutbox(
    daoBundle: MemoMutationDaoBundle,
    command: MemoLifecycleCommand,
): Long {
    val sourceMemo = command.sourceMemo
    var outboxId = 0L
    daoBundle.runInTransaction {
        daoBundle.memoWriteDao.deleteMemoById(sourceMemo.id)
        daoBundle.memoTagDao.deleteTagRefsByMemoId(sourceMemo.id)
        val trashProjection = MemoProjectionProjector.projectTrash(sourceMemo.copy(isDeleted = true))
        daoBundle.memoTrashDao.insertTrashMemo(trashProjection.entity)
        daoBundle.memoImageDao.replaceImageRefsForTrashMemo(trashProjection)
        outboxId = daoBundle.memoOutboxDao.insertMemoFileOutbox(buildDeleteOutbox(command))
    }
    return outboxId
}

internal suspend fun restoreMemoFromTrashWithOutbox(
    daoBundle: MemoMutationDaoBundle,
    command: MemoLifecycleCommand,
): Long {
    val sourceMemo = command.sourceMemo
    var outboxId = 0L
    daoBundle.runInTransaction {
        persistMainMemoProjection(
            daoBundle,
            MemoProjectionProjector.projectActive(sourceMemo.copy(isDeleted = false)),
        )
        daoBundle.memoTrashDao.deleteTrashMemoById(sourceMemo.id)
        outboxId = daoBundle.memoOutboxDao.insertMemoFileOutbox(buildRestoreOutbox(command))
    }
    return outboxId
}

internal suspend fun enqueuePermanentDeleteWithOutbox(
    daoBundle: MemoMutationDaoBundle,
    command: MemoLifecycleCommand,
): Long {
    var outboxId = 0L
    daoBundle.runInTransaction {
        outboxId = daoBundle.memoOutboxDao.insertMemoFileOutbox(buildPermanentDeleteOutbox(command))
    }
    return outboxId
}

internal suspend fun restoreMemoRevisionWithOutbox(
    daoBundle: MemoMutationDaoBundle,
    command: MemoLifecycleCommand,
): Long {
    val target = requireNotNull(command.revisionRestoreTarget) {
        "Revision restore DB transaction requires target revision: ${command.metadata.operationId.value}"
    }
    var outboxId = 0L
    daoBundle.runInTransaction {
        when (target.lifecycleState) {
            MemoRevisionLifecycleState.ACTIVE -> {
                val projection = MemoProjectionProjector.projectActive(target.memo.copy(isDeleted = false))
                persistMainMemoProjection(daoBundle, projection)
                daoBundle.memoTrashDao.deleteTrashMemoById(target.memo.id)
            }
            MemoRevisionLifecycleState.TRASHED -> {
                daoBundle.memoWriteDao.deleteMemoById(target.memo.id)
                daoBundle.memoTagDao.deleteTagRefsByMemoId(target.memo.id)
                val projection = MemoProjectionProjector.projectTrash(target.memo.copy(isDeleted = true))
                daoBundle.memoTrashDao.insertTrashMemo(projection.entity)
                daoBundle.memoImageDao.replaceImageRefsForTrashMemo(projection)
            }
            MemoRevisionLifecycleState.DELETED -> {
                daoBundle.memoWriteDao.deleteMemoById(target.memo.id)
                daoBundle.memoTagDao.deleteTagRefsByMemoId(target.memo.id)
                daoBundle.memoImageDao.deleteImageRefsByMemoId(target.memo.id)
                daoBundle.memoTrashDao.deleteTrashMemoById(target.memo.id)
            }
        }
        outboxId = daoBundle.memoOutboxDao.insertMemoFileOutbox(buildVersionRestoreOutbox(command))
    }
    return outboxId
}

internal suspend fun enqueueClearTrashWithOutbox(daoBundle: MemoMutationDaoBundle): Int {
    val trashMemos = daoBundle.memoTrashDao.getDeletedMemos()
    if (trashMemos.isEmpty()) return 0

    daoBundle.runInTransaction {
        trashMemos.forEach { trashMemo ->
            daoBundle.memoOutboxDao.insertMemoFileOutbox(
                buildPermanentDeleteOutbox(MemoLifecycleCommand.permanentDelete(trashMemo.toDomain())),
            )
        }
    }
    return trashMemos.size
}

internal suspend fun markPermanentDeleteCompletedInDb(
    daoBundle: MemoMutationDaoBundle,
    memoId: String,
) {
    daoBundle.runInTransaction {
        daoBundle.memoImageDao.deleteImageRefsByMemoId(memoId)
        daoBundle.memoTrashDao.deleteTrashMemoById(memoId)
    }
}
