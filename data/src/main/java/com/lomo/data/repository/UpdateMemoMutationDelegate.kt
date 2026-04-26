package com.lomo.data.repository

import com.lomo.data.local.entity.MemoEntity
import com.lomo.domain.model.Memo

internal class UpdateMemoMutationDelegate(
    private val runtime: MemoMutationRuntime,
    private val storageFormatProvider: MemoStorageFormatProvider,
) : MemoUpdateMutationOperations {
    override suspend fun updateMemo(
        memo: Memo,
        newContent: String,
    ) = runtime.mutationGate.withLock {
        requireUpdatedMemoContent(newContent)
        when {
            runtime.daoBundle.memoDao.getMemo(memo.id) == null -> Unit

            flushMemoUpdateToFileLocked(memo, newContent) -> {
                val finalUpdatedMemo = buildUpdatedMemo(runtime, storageFormatProvider, memo, newContent)
                persistMainMemoEntity(runtime.daoBundle, MemoEntity.fromDomain(finalUpdatedMemo))
                cleanupAttachmentsRemovedByEdit(runtime, memo, finalUpdatedMemo)
                runtime.memoVersionRecorder.enqueueLocalRevision(
                    memo = finalUpdatedMemo,
                    lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.ACTIVE,
                    origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_EDIT,
                )
            }

            else -> {
                throw UnsafeWorkspaceMutationException("Unable to update memo safely: ${memo.id}")
            }
        }
    }

    override suspend fun updateMemoInDb(
        memo: Memo,
        newContent: String,
    ): Long? =
        runtime.mutationGate.withLock {
            requireUpdatedMemoContent(newContent)
            val sourceMemo = runtime.daoBundle.memoDao.getMemo(memo.id)?.toDomain()
            when {
                sourceMemo == null -> null
                else -> {
                    val finalUpdatedMemo = buildUpdatedMemo(runtime, storageFormatProvider, sourceMemo, newContent)
                    val outboxId =
                        persistMemoWithOutbox(
                            daoBundle = runtime.daoBundle,
                            memo = MemoEntity.fromDomain(finalUpdatedMemo),
                            outbox = buildUpdateOutbox(sourceMemo, newContent),
                        )
                    cleanupAttachmentsRemovedByEdit(runtime, sourceMemo, finalUpdatedMemo)
                    runtime.memoVersionRecorder.enqueueLocalRevision(
                        memo = finalUpdatedMemo,
                        lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.ACTIVE,
                        origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_EDIT,
                    )
                    outboxId
                }
            }
        }

    override suspend fun flushMemoUpdateToFile(
        memo: Memo,
        newContent: String,
    ): Boolean =
        runtime.mutationGate.withLock {
            flushMemoUpdateToFileLocked(memo, newContent)
        }

    private suspend fun flushMemoUpdateToFileLocked(
        memo: Memo,
        newContent: String,
    ): Boolean {
        requireUpdatedMemoContent(newContent)
        return flushMainMemoUpdateToFile(runtime, storageFormatProvider, memo, newContent)
    }
}

private suspend fun cleanupAttachmentsRemovedByEdit(
    runtime: MemoMutationRuntime,
    previousMemo: Memo,
    updatedMemo: Memo,
) {
    val removed = previousMemo.imageUrls.toSet() - updatedMemo.imageUrls.toSet()
    if (removed.isEmpty()) return
    deleteOrphanAttachments(
        paths = removed.toList(),
        excludeMemoId = updatedMemo.id,
        memoSearchDao = runtime.memoSearchDao,
        mediaStorageDataSource = runtime.mediaStorageDataSource,
        s3LocalChangeRecorder = runtime.s3LocalChangeRecorder,
        webDavLocalChangeRecorder = runtime.webDavLocalChangeRecorder,
    )
}

internal suspend fun buildUpdatedMemo(
    runtime: MemoMutationRuntime,
    storageFormatProvider: MemoStorageFormatProvider,
    memo: Memo,
    newContent: String,
): Memo {
    val timeString = storageFormatProvider.formatTime(memo.timestamp)
    val updatedAt = nextUpdatedAt(memo.updatedAt)
    return memo.copy(
        content = newContent,
        updatedAt = updatedAt,
        rawContent = "- $timeString $newContent",
        tags = runtime.textProcessor.extractTags(newContent),
        imageUrls = runtime.textProcessor.extractInlineAttachments(newContent),
    )
}

internal fun nextUpdatedAt(previousUpdatedAt: Long): Long {
    val now = System.currentTimeMillis()
    return if (now > previousUpdatedAt) now else previousUpdatedAt + 1
}

private fun requireUpdatedMemoContent(newContent: String) {
    require(newContent.isNotBlank()) { "Memo update content must not be blank" }
}
