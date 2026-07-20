package com.lomo.data.repository

import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.local.projection.ActiveMemoProjection
import com.lomo.data.local.projection.MemoProjectionProjector
import com.lomo.data.local.projection.toAttachmentContentAnalysis
import com.lomo.data.util.MarkdownWorkspaceContentProjector
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
        val trashed = sourceMemo.copy(isDeleted = true)
        // Content is unchanged; reuse memo attachment/tag facts — no second render.
        val trashProjection =
            MemoProjectionProjector.projectTrash(trashed, trashed.toAttachmentContentAnalysis())
        daoBundle.memoTrashDao.insertTrashMemo(trashProjection.entity)
        daoBundle.memoImageDao.replaceImageRefsForTrashMemo(trashProjection)
        outboxId = daoBundle.memoOutboxDao.insertMemoFileOutbox(buildDeleteOutbox(command))
    }
    return outboxId
}

internal suspend fun restoreMemoFromTrashWithOutbox(
    daoBundle: MemoMutationDaoBundle,
    command: MemoLifecycleCommand,
    contentProjector: MarkdownWorkspaceContentProjector,
): Long {
    val sourceMemo = command.sourceMemo
    var outboxId = 0L
    daoBundle.runInTransaction {
        // Active query flags (hasTodo/hasUrl) are not stored on trash rows; one owner analyze of
        // the restored free body projects them for Room without a second pass on the same call.
        val active = sourceMemo.copy(isDeleted = false)
        val analysis = contentProjector.analyze(active.content)
        persistMainMemoProjection(
            daoBundle,
            MemoProjectionProjector.projectActive(active, analysis),
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
    contentProjector: MarkdownWorkspaceContentProjector,
): Long {
    val target = requireNotNull(command.revisionRestoreTarget) {
        "Revision restore DB transaction requires target revision: ${command.metadata.operationId.value}"
    }
    var outboxId = 0L
    daoBundle.runInTransaction {
        when (target.lifecycleState) {
            MemoRevisionLifecycleState.ACTIVE -> {
                val active = target.memo.copy(isDeleted = false)
                val analysis = contentProjector.analyze(active.content)
                val projection = MemoProjectionProjector.projectActive(active, analysis)
                persistMainMemoProjection(daoBundle, projection)
                daoBundle.memoTrashDao.deleteTrashMemoById(target.memo.id)
            }
            MemoRevisionLifecycleState.TRASHED -> {
                daoBundle.memoWriteDao.deleteMemoById(target.memo.id)
                daoBundle.memoTagDao.deleteTagRefsByMemoId(target.memo.id)
                val trashed = target.memo.copy(isDeleted = true)
                val projection =
                    MemoProjectionProjector.projectTrash(trashed, trashed.toAttachmentContentAnalysis())
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
        // One shard-clear row per date drains first and deletes the entire trash shard file in a
        // single I/O. The per-memo permanent-delete rows below then complete idempotently (their
        // block is already gone), so clearing a large trash no longer rewrites each shard once per
        // memo. Destruction still happens in the drain via command-owned rows.
        trashMemos.map(TrashMemoEntity::date).distinct().forEach { dateKey ->
            daoBundle.memoOutboxDao.insertMemoFileOutbox(buildClearTrashShardOutbox(dateKey))
        }
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
