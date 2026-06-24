package com.lomo.data.repository

import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.data.util.MemoLocalDateResolver
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.usecase.MemoContentAnalyzer

internal fun buildUpdateOutbox(
    sourceMemo: Memo,
    newContent: String,
): MemoFileOutboxEntity = MemoLifecycleCommand.updateMemo(sourceMemo, newContent).toOutboxEntity()

internal fun buildDeleteOutbox(command: MemoLifecycleCommand): MemoFileOutboxEntity {
    require(command.operation == MemoLifecycleOperation.DELETE_TO_TRASH) {
        "Delete outbox requires delete-to-trash lifecycle command: ${command.metadata.operationId.value}"
    }
    return command.toOutboxEntity()
}

internal fun buildRestoreOutbox(command: MemoLifecycleCommand): MemoFileOutboxEntity {
    require(command.operation == MemoLifecycleOperation.RESTORE_FROM_TRASH) {
        "Restore outbox requires restore-from-trash lifecycle command: ${command.metadata.operationId.value}"
    }
    return command.toOutboxEntity()
}

internal fun buildPermanentDeleteOutbox(command: MemoLifecycleCommand): MemoFileOutboxEntity {
    require(command.operation == MemoLifecycleOperation.PERMANENT_DELETE) {
        "Permanent-delete outbox requires permanent-delete lifecycle command: ${command.metadata.operationId.value}"
    }
    return command.toOutboxEntity()
}

internal fun buildVersionRestoreOutbox(command: MemoLifecycleCommand): MemoFileOutboxEntity {
    require(command.operation == MemoLifecycleOperation.VERSION_RESTORE) {
        "Version-restore outbox requires version-restore lifecycle command: ${command.metadata.operationId.value}"
    }
    return command.toOutboxEntity()
}

/**
 * A shard-scoped clear-trash row. Draining it deletes the whole trash shard file in one I/O, so the
 * per-memo PERMANENT_DELETE rows enqueued alongside it complete idempotently (their block is already
 * gone) instead of each re-rewriting the same file. Carries no memo, only the shard date key.
 */
internal fun buildClearTrashShardOutbox(dateKey: String): MemoFileOutboxEntity {
    val identity = com.lomo.data.local.entity.MemoFileOutboxIdentityPolicy.forClearTrashShard(dateKey)
    return MemoFileOutboxEntity(
        operation = MemoFileOutboxOp.CLEAR_TRASH_SHARD,
        operationId = identity.operationId,
        idempotencyKey = identity.idempotencyKey,
        memoId = "clear-trash-shard:$dateKey",
        memoDate = dateKey,
        memoTimestamp = 0L,
        memoRawContent = "",
        newContent = null,
        createRawContent = null,
    )
}

internal fun outboxSourceMemo(item: MemoFileOutboxEntity): Memo {
    val content = item.sourceContent()
    val contentAnalysis = MemoContentAnalyzer.analyze(content)
    return Memo(
        id = item.memoId,
        timestamp = item.memoTimestamp,
        content = content,
        rawContent = item.memoRawContent,
        dateKey = item.memoDate,
        localDate = MemoLocalDateResolver.resolve(item.memoDate),
        tags = contentAnalysis.tags,
        imageUrls = contentAnalysis.imageUrls + contentAnalysis.audioUrls,
        isDeleted = item.operation == MemoFileOutboxOp.RESTORE || item.operation == MemoFileOutboxOp.PERMANENT_DELETE,
    )
}

internal fun MemoFileOutboxEntity.toLifecycleCommand(): MemoLifecycleCommand =
    when (operation) {
        MemoFileOutboxOp.CREATE -> MemoLifecycleCommand.createMemo(outboxSourceMemo(this))
        MemoFileOutboxOp.UPDATE ->
            MemoLifecycleCommand.updateMemo(
                sourceMemo = outboxSourceMemo(this),
                targetContent = requireNotNull(newContent) { "UPDATE outbox requires newContent for memo $memoId" },
            )
        MemoFileOutboxOp.DELETE -> MemoLifecycleCommand.deleteToTrash(outboxSourceMemo(this))
        MemoFileOutboxOp.RESTORE -> MemoLifecycleCommand.restoreFromTrash(outboxSourceMemo(this))
        MemoFileOutboxOp.PERMANENT_DELETE -> MemoLifecycleCommand.permanentDelete(outboxSourceMemo(this))
        MemoFileOutboxOp.VERSION_RESTORE -> decodeRevisionRestoreOutboxCommand(this)
        MemoFileOutboxOp.CLEAR_TRASH_SHARD ->
            error("CLEAR_TRASH_SHARD is a shard-scoped row with no per-memo lifecycle command: $memoDate")
    }.also { command ->
        command.requireDurableIdentity(
            operationId = operationId,
            idempotencyKey = idempotencyKey,
        )
    }

private fun MemoFileOutboxEntity.sourceContent(): String =
    when (operation) {
        MemoFileOutboxOp.DELETE,
        MemoFileOutboxOp.PERMANENT_DELETE,
        MemoFileOutboxOp.UPDATE,
        MemoFileOutboxOp.VERSION_RESTORE,
        -> contentFromRawMemoSource(memoRawContent)
        MemoFileOutboxOp.RESTORE -> requireNotNull(newContent) { "Outbox RESTORE requires newContent for memo $memoId" }
        MemoFileOutboxOp.CREATE -> requireNotNull(newContent) { "Outbox CREATE requires newContent for memo $memoId" }
        MemoFileOutboxOp.CLEAR_TRASH_SHARD ->
            error("CLEAR_TRASH_SHARD carries no memo content: $memoDate")
    }

internal fun contentFromRawMemoSource(rawContent: String): String {
    val lines = rawContent.lines()
    val header = lines.firstOrNull()?.let(StorageTimestampFormats::parseMemoHeaderLine)
    return if (header == null) {
        rawContent.trim()
    } else {
        buildString {
            append(header.contentPart)
            for (index in 1 until lines.size) {
                if (isNotEmpty()) {
                    append('\n')
                }
                append(lines[index])
            }
        }.trim()
    }
}
