package com.lomo.data.repository

import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxOp
import java.util.UUID

internal class MemoOutboxMutationDelegate(
    private val runtime: MemoMutationRuntime,
    private val storageFormatProvider: MemoStorageFormatProvider,
) : MemoOutboxMutationOperations {
    override suspend fun hasPendingMemoFileOutbox(): Boolean =
        runtime.daoBundle.memoOutboxDao.getMemoFileOutboxCount() > 0

    override suspend fun nextMemoFileOutbox(): MemoFileOutboxEntity? {
        val now = System.currentTimeMillis()
        return runtime.daoBundle.memoOutboxDao.claimNextMemoFileOutbox(
            claimToken = UUID.randomUUID().toString(),
            claimedAt = now,
            staleBefore = now - OUTBOX_CLAIM_STALE_MS,
        )
    }

    override suspend fun acknowledgeMemoFileOutbox(id: Long) {
        runtime.daoBundle.memoOutboxDao.deleteMemoFileOutboxById(id)
    }

    override suspend fun markMemoFileOutboxFailed(
        id: Long,
        throwable: Throwable?,
    ) {
        runtime.daoBundle.memoOutboxDao.markMemoFileOutboxFailed(
            id = id,
            updatedAt = System.currentTimeMillis(),
            lastError = throwable?.message?.take(MAX_OUTBOX_ERROR_LENGTH),
        )
    }

    override suspend fun flushMemoFileOutbox(item: MemoFileOutboxEntity): Boolean =
        runtime.mutationGate.withLock {
            when (item.operation) {
                MemoFileOutboxOp.CREATE -> flushCreateFromOutbox(runtime, item)
                MemoFileOutboxOp.UPDATE -> {
                    val newContent = item.newContent ?: return@withLock false
                    flushMainMemoUpdateToFile(runtime, storageFormatProvider, outboxSourceMemo(item), newContent)
                }

                MemoFileOutboxOp.DELETE -> flushDeleteFromOutbox(runtime, item)
                MemoFileOutboxOp.RESTORE ->
                    runtime.trashMutationHandler.restoreFromTrashFileOnly(outboxSourceMemo(item))
            }
        }
}

/**
 * Outbox-side DELETE flush: tries the normal main-rewrite + trash-append flow; on a retry after
 * a crash between those two steps, falls back to [ensureMemoPresentInTrashFile] so the queued
 * deletion still finishes.
 */
internal suspend fun flushDeleteFromOutbox(
    runtime: MemoMutationRuntime,
    item: MemoFileOutboxEntity,
): Boolean {
    val memo = outboxSourceMemo(item)
    if (runtime.trashMutationHandler.moveToTrashFileOnly(memo)) {
        return true
    }
    return runtime.trashMutationHandler.ensureMemoPresentInTrashFile(memo)
}

internal suspend fun flushCreateFromOutbox(
    runtime: MemoMutationRuntime,
    item: MemoFileOutboxEntity,
): Boolean {
    requireSafeMemoDateKey(item.memoDate)
    val createRawContent = item.createRawContent ?: return false
    val filename = item.memoDate + ".md"
    appendMainMemoContentAndUpdateState(
        runtime = runtime,
        filename = filename,
        rawContent = createRawContent,
    )
    return true
}
