package com.lomo.data.repository

import com.lomo.data.local.entity.MemoFileOutboxEntity
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
            val command = item.toLifecycleCommand()
            when (command.operation) {
                MemoLifecycleOperation.CREATE -> flushCreateFromOutbox(runtime, command)
                MemoLifecycleOperation.UPDATE -> flushUpdateFromOutbox(runtime, storageFormatProvider, command)
                MemoLifecycleOperation.DELETE_TO_TRASH -> flushDeleteFromOutbox(runtime, command)
                MemoLifecycleOperation.RESTORE_FROM_TRASH -> flushRestoreFromOutbox(runtime, command)
                MemoLifecycleOperation.PERMANENT_DELETE -> flushPermanentDeleteFromOutbox(runtime, command)
                MemoLifecycleOperation.VERSION_RESTORE -> flushVersionRestoreFromOutbox(runtime, command)
            }
    }
}

internal class MemoOutboxLifecycleCompletionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Outbox-side DELETE flush: tries the normal main-rewrite + trash-append flow; on a retry after
 * a crash between those two steps, falls back to [ensureMemoPresentInTrashFile] so the queued
 * deletion still finishes.
 */
internal suspend fun flushDeleteFromOutbox(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
): Boolean =
    completeDeleteOutboxFlush(runtime, command) {
        val memo = command.sourceMemo
        runtime.trashMutationHandler.moveToTrashFileOnly(memo) ||
            runtime.trashMutationHandler.ensureMemoPresentInTrashFile(command)
    }

private suspend fun completeDeleteOutboxFlush(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
    finishFileMutation: suspend () -> Boolean,
): Boolean {
    require(command.operation == MemoLifecycleOperation.DELETE_TO_TRASH) {
        "DELETE outbox completion requires delete-to-trash lifecycle command: ${command.metadata.operationId.value}"
    }
    val completed = finishFileMutation()
    if (completed) {
        recordMainMemoStateAfterTrashMove(runtime, command.sourceMemo)
    }
    return completed
}

internal suspend fun flushRestoreFromOutbox(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
): Boolean {
    val memo = command.sourceMemo
    val restored = runtime.trashMutationHandler.restoreFromTrashFileOnly(memo)
    if (restored) {
        runtime.s3LocalChangeRecorder.recordMemoUpsert(command.filename)
        runtime.webDavLocalChangeRecorder.recordMemoUpsert(command.filename)
    }
    return restored
}

internal suspend fun flushCreateFromOutbox(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
): Boolean {
    require(command.operation == MemoLifecycleOperation.CREATE) {
        "CREATE outbox completion requires create lifecycle command: ${command.metadata.operationId.value}"
    }
    recordOutboxVersionHandoff(runtime, command)
    runtime.workspaceStore.appendActiveMemoBlock(
        filename = command.filename,
        rawContent = command.sourceMemo.rawContent,
    )
    runtime.s3LocalChangeRecorder.recordMemoUpsert(command.filename)
    runtime.webDavLocalChangeRecorder.recordMemoUpsert(command.filename)
    return true
}

internal suspend fun flushUpdateFromOutbox(
    runtime: MemoMutationRuntime,
    storageFormatProvider: MemoStorageFormatProvider,
    command: MemoLifecycleCommand,
): Boolean {
    require(command.operation == MemoLifecycleOperation.UPDATE) {
        "UPDATE outbox completion requires update lifecycle command: ${command.metadata.operationId.value}"
    }
    recordOutboxVersionHandoff(runtime, command)
    val updated =
        runtime.workspaceStore.updateActiveMemoBlock(
        memo = command.sourceMemo,
        newContent =
            requireNotNull(command.targetContent) {
                "UPDATE outbox completion requires target content: ${command.metadata.operationId.value}"
            },
        timestampText = storageFormatProvider.formatTime(command.sourceMemo.timestamp),
    )
    val applied = updated == MemoWorkspaceBlockMutationResult.Applied
    if (applied) {
        runtime.s3LocalChangeRecorder.recordMemoUpsert(command.filename)
        runtime.webDavLocalChangeRecorder.recordMemoUpsert(command.filename)
    }
    return applied
}

internal suspend fun flushPermanentDeleteFromOutbox(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
): Boolean {
    require(command.operation == MemoLifecycleOperation.PERMANENT_DELETE) {
        "PERMANENT_DELETE outbox completion requires permanent-delete lifecycle command: " +
            command.metadata.operationId.value
    }
    val completionCommand = command.resolveDurablePermanentDeleteCommand(runtime)
    val fileCompletion = runtime.trashMutationHandler.deleteFromTrashFileOnly(completionCommand)
    when (fileCompletion) {
        PermanentDeleteTrashFileCompletion.Completed -> Unit
        PermanentDeleteTrashFileCompletion.MissingTrashBlock ->
            throw MemoOutboxLifecycleCompletionException(
                "PERMANENT_DELETE missing trash block for memo ${completionCommand.sourceMemo.id}: " +
                    completionCommand.metadata.operationId.value,
            )
    }
    recordPermanentDeleteVersionHandoff(runtime, completionCommand)
    runtime.s3LocalChangeRecorder.recordMemoDelete(completionCommand.filename)
    runtime.webDavLocalChangeRecorder.recordMemoDelete(completionCommand.filename)
    deleteOrphanAttachments(
        paths = completionCommand.sourceMemo.imageUrls,
        excludeMemoId = completionCommand.sourceMemo.id,
        memoStatisticsDao = runtime.memoStatisticsDao,
        mediaStorageDataSource = runtime.mediaStorageDataSource,
        s3LocalChangeRecorder = runtime.s3LocalChangeRecorder,
        webDavLocalChangeRecorder = runtime.webDavLocalChangeRecorder,
    )
    markPermanentDeleteCompletedInDb(
        daoBundle = runtime.daoBundle,
        memoId = completionCommand.sourceMemo.id,
    )
    return true
}

private suspend fun MemoLifecycleCommand.resolveDurablePermanentDeleteCommand(
    runtime: MemoMutationRuntime,
): MemoLifecycleCommand {
    val persistedMemo =
        runtime.daoBundle.memoTrashDao
            .getTrashMemo(sourceMemo.id)
            ?.toDomain()
            ?: return this
    return MemoLifecycleCommand.permanentDelete(persistedMemo).also { persistedCommand ->
        persistedCommand.requireDurableIdentity(
            operationId = metadata.operationId.value,
            idempotencyKey = metadata.idempotencyKey.value,
        )
    }
}

private suspend fun recordPermanentDeleteVersionHandoff(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
) {
    runtime.memoVersionRecorder.recordLocalRevision(
        memo = command.versionMemo,
        lifecycleState = command.operation.versionLifecycleState,
        origin = command.operation.versionOrigin,
    )
}

private suspend fun recordOutboxVersionHandoff(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
) {
    val memo = requireActiveMemoForVersionHandoff(runtime, command)
    runtime.memoVersionRecorder.recordLocalRevision(
        memo = memo,
        lifecycleState = command.operation.versionLifecycleState,
        origin = command.operation.versionOrigin,
    )
}

private suspend fun requireActiveMemoForVersionHandoff(
    runtime: MemoMutationRuntime,
    command: MemoLifecycleCommand,
): com.lomo.domain.model.Memo =
    requireNotNull(runtime.daoBundle.memoDao.getMemo(command.sourceMemo.id)?.toDomain()) {
        "Memo outbox version handoff requires durable active memo state: ${command.sourceMemo.id}"
    }
