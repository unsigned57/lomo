package com.lomo.data.repository

import com.lomo.data.local.projection.MemoProjectionProjector
import com.lomo.domain.model.Memo

internal class UpdateMemoMutationDelegate(
    private val runtime: MemoMutationRuntime,
    private val storageFormatProvider: MemoStorageFormatProvider,
) : MemoUpdateMutationOperations {
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
                            memoProjection = MemoProjectionProjector.projectActive(finalUpdatedMemo),
                            outbox = buildUpdateOutbox(sourceMemo, newContent),
                    )
                    cleanupAttachmentsRemovedByEdit(runtime, sourceMemo, finalUpdatedMemo)
                    outboxId
                }
            }
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
        memoStatisticsDao = runtime.memoStatisticsDao,
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
