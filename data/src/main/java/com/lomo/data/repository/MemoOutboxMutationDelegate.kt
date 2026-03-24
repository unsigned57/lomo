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
        when (item.operation) {
            MemoFileOutboxOp.CREATE -> flushCreateFromOutbox(runtime, item)
            MemoFileOutboxOp.UPDATE -> {
                val newContent = item.newContent ?: return false
                flushMainMemoUpdateToFile(runtime, storageFormatProvider, outboxSourceMemo(item), newContent)
            }

            MemoFileOutboxOp.DELETE -> runtime.trashMutationHandler.moveToTrashFileOnly(outboxSourceMemo(item))
            MemoFileOutboxOp.RESTORE -> runtime.trashMutationHandler.restoreFromTrashFileOnly(outboxSourceMemo(item))
            else -> false
        }
}

internal suspend fun flushCreateFromOutbox(
    runtime: MemoMutationRuntime,
    item: MemoFileOutboxEntity,
): Boolean {
    val createRawContent = item.createRawContent ?: return false
    val filename = item.memoDate + ".md"
    appendMainMemoContentAndUpdateState(
        runtime = runtime,
        filename = filename,
        rawContent = createRawContent,
    )
    return true
}
