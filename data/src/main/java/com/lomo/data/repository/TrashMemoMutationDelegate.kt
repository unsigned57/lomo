package com.lomo.data.repository

import com.lomo.domain.model.Memo

internal class TrashMemoMutationDelegate(
    private val runtime: MemoMutationRuntime,
) : MemoTrashMutationOperations {
    override suspend fun deleteMemo(memo: Memo) =
        runtime.mutationGate.withLock {
            runtime.trashMutationHandler.moveToTrash(memo)
        }

    override suspend fun deleteMemoInDb(memo: Memo): Long? =
        runtime.mutationGate.withLock {
            val sourceMemo = runtime.daoBundle.memoDao.getMemo(memo.id)?.toDomain() ?: return@withLock null
            val command = MemoLifecycleCommand.deleteToTrash(sourceMemo)
            val outboxId =
                moveMemoToTrashWithOutbox(
                    daoBundle = runtime.daoBundle,
                    command = command,
                )
            runtime.memoVersionRecorder.enqueueLocalRevision(
                memo = command.versionMemo,
                lifecycleState = command.operation.versionLifecycleState,
                origin = command.operation.versionOrigin,
            )
            outboxId
        }

    override suspend fun flushDeleteMemoToFile(memo: Memo): Boolean =
        runtime.mutationGate.withLock {
            runtime.trashMutationHandler.moveToTrashFileOnly(memo).also { moved ->
                if (moved) {
                    recordMainMemoStateAfterTrashMove(runtime, memo)
                }
            }
        }

    override suspend fun restoreMemo(memo: Memo) =
        runtime.mutationGate.withLock {
            runtime.trashMutationHandler.restoreFromTrash(memo)
        }

    override suspend fun restoreMemoInDb(memo: Memo): Long? =
        runtime.mutationGate.withLock {
            val sourceMemo =
                runtime.daoBundle.memoTrashDao.getTrashMemo(memo.id)?.toDomain() ?: return@withLock null
            val command = MemoLifecycleCommand.restoreFromTrash(sourceMemo)
            val outboxId =
                restoreMemoFromTrashWithOutbox(
                    daoBundle = runtime.daoBundle,
                    command = command,
                )
            runtime.memoVersionRecorder.enqueueLocalRevision(
                memo = command.versionMemo,
                lifecycleState = command.operation.versionLifecycleState,
                origin = command.operation.versionOrigin,
            )
            outboxId
        }

    override suspend fun flushRestoreMemoToFile(memo: Memo): Boolean =
        runtime.mutationGate.withLock {
            runtime.trashMutationHandler.restoreFromTrashFileOnly(memo).also { restored ->
                if (restored) {
                    runtime.s3LocalChangeRecorder.recordMemoUpsert("${memo.dateKey}.md")
                    runtime.webDavLocalChangeRecorder.recordMemoUpsert("${memo.dateKey}.md")
                }
            }
        }

    override suspend fun deletePermanentlyInDb(memo: Memo): Long? =
        runtime.mutationGate.withLock {
            val sourceMemo =
                runtime.daoBundle.memoTrashDao.getTrashMemo(memo.id)?.toDomain() ?: return@withLock null
            enqueuePermanentDeleteWithOutbox(
                daoBundle = runtime.daoBundle,
                command = MemoLifecycleCommand.permanentDelete(sourceMemo),
            )
        }

    override suspend fun clearTrash(): Int =
        runtime.mutationGate.withLock {
            enqueueClearTrashWithOutbox(runtime.daoBundle)
        }
}

internal suspend fun recordMainMemoStateAfterTrashMove(
    runtime: MemoMutationRuntime,
    memo: Memo,
) {
    val filename = "${memo.dateKey}.md"
    val mainState = runtime.localFileStateDao.getByFilename(filename, false)
    if (mainState != null) {
        runtime.s3LocalChangeRecorder.recordMemoUpsert(filename)
        runtime.webDavLocalChangeRecorder.recordMemoUpsert(filename)
    } else {
        runtime.s3LocalChangeRecorder.recordMemoDelete(filename)
        runtime.webDavLocalChangeRecorder.recordMemoDelete(filename)
    }
}
