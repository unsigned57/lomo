package com.lomo.data.repository

import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxIdentityPolicy
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@JvmInline
internal value class MemoLifecycleOperationId(
    val value: String,
)

@JvmInline
internal value class MemoLifecycleIdempotencyKey(
    val value: String,
)

internal data class MemoLifecycleCommandMetadata(
    val operationId: MemoLifecycleOperationId,
    val idempotencyKey: MemoLifecycleIdempotencyKey,
    val sourceRevisionHash: String,
    val targetRevisionHash: String?,
    val sourceRevisionId: String? = null,
    val targetRevisionId: String? = null,
)

internal enum class MemoLifecycleOperation(
    val slug: String,
    val outboxOperation: MemoFileOutboxOp,
    val versionLifecycleState: MemoRevisionLifecycleState,
    val versionOrigin: MemoRevisionOrigin,
    val targetDeleted: Boolean,
) {
    CREATE(
        slug = "create",
        outboxOperation = MemoFileOutboxOp.CREATE,
        versionLifecycleState = MemoRevisionLifecycleState.ACTIVE,
        versionOrigin = MemoRevisionOrigin.LOCAL_CREATE,
        targetDeleted = false,
    ),
    UPDATE(
        slug = "update",
        outboxOperation = MemoFileOutboxOp.UPDATE,
        versionLifecycleState = MemoRevisionLifecycleState.ACTIVE,
        versionOrigin = MemoRevisionOrigin.LOCAL_EDIT,
        targetDeleted = false,
    ),
    DELETE_TO_TRASH(
        slug = "delete-to-trash",
        outboxOperation = MemoFileOutboxOp.DELETE,
        versionLifecycleState = MemoRevisionLifecycleState.TRASHED,
        versionOrigin = MemoRevisionOrigin.LOCAL_TRASH,
        targetDeleted = true,
    ),
    RESTORE_FROM_TRASH(
        slug = "restore-from-trash",
        outboxOperation = MemoFileOutboxOp.RESTORE,
        versionLifecycleState = MemoRevisionLifecycleState.ACTIVE,
        versionOrigin = MemoRevisionOrigin.LOCAL_RESTORE,
        targetDeleted = false,
    ),
    PERMANENT_DELETE(
        slug = "permanent-delete",
        outboxOperation = MemoFileOutboxOp.PERMANENT_DELETE,
        versionLifecycleState = MemoRevisionLifecycleState.DELETED,
        versionOrigin = MemoRevisionOrigin.LOCAL_DELETE,
        targetDeleted = true,
    ),
    VERSION_RESTORE(
        slug = "version-restore",
        outboxOperation = MemoFileOutboxOp.VERSION_RESTORE,
        versionLifecycleState = MemoRevisionLifecycleState.ACTIVE,
        versionOrigin = MemoRevisionOrigin.LOCAL_RESTORE,
        targetDeleted = false,
    ),
}

internal data class MemoRevisionRestoreTarget(
    val revisionId: String,
    val lifecycleState: MemoRevisionLifecycleState,
    val memo: Memo,
    val rawContent: String,
)

internal data class MemoLifecycleCommand(
    val operation: MemoLifecycleOperation,
    val sourceMemo: Memo,
    val metadata: MemoLifecycleCommandMetadata,
    val targetContent: String? = null,
    val revisionRestoreTarget: MemoRevisionRestoreTarget? = null,
) {
    val filename: String
        get() = "${(revisionRestoreTarget?.memo ?: sourceMemo).dateKey}.md"

    val sourceFilename: String
        get() = "${sourceMemo.dateKey}.md"

    val versionMemo: Memo
        get() =
            revisionRestoreTarget?.let { target ->
                target.memo.copy(isDeleted = target.lifecycleState != MemoRevisionLifecycleState.ACTIVE)
            } ?: sourceMemo.copy(isDeleted = operation.targetDeleted)

    val versionLifecycleState: MemoRevisionLifecycleState
        get() = revisionRestoreTarget?.lifecycleState ?: operation.versionLifecycleState

    val versionOrigin: MemoRevisionOrigin
        get() = operation.versionOrigin

    fun toOutboxEntity(): MemoFileOutboxEntity {
        val identity = metadata.outboxIdentity()
        val outboxMemo = revisionRestoreTarget?.memo ?: sourceMemo
        return MemoFileOutboxEntity(
            operation = operation.outboxOperation,
            operationId = identity.operationId,
            idempotencyKey = identity.idempotencyKey,
            memoId = sourceMemo.id,
            memoDate = outboxMemo.dateKey,
            memoTimestamp = outboxMemo.timestamp,
            memoRawContent = sourceMemo.rawContent,
            newContent =
                when (operation) {
                    MemoLifecycleOperation.CREATE -> sourceMemo.content
                    MemoLifecycleOperation.UPDATE -> requireNotNull(targetContent) {
                        "Update lifecycle command requires target content: ${sourceMemo.id}"
                    }
                    MemoLifecycleOperation.DELETE_TO_TRASH -> null
                    MemoLifecycleOperation.RESTORE_FROM_TRASH -> sourceMemo.content
                    MemoLifecycleOperation.PERMANENT_DELETE -> null
                    MemoLifecycleOperation.VERSION_RESTORE ->
                        requireNotNull(revisionRestoreTarget) {
                            "Version-restore lifecycle command requires target revision: ${sourceMemo.id}"
                        }.toOutboxPayload(sourceMemo = sourceMemo, metadata = metadata)
                },
            createRawContent =
                when (operation) {
                    MemoLifecycleOperation.CREATE -> sourceMemo.rawContent
                    MemoLifecycleOperation.VERSION_RESTORE ->
                        requireNotNull(revisionRestoreTarget) {
                            "Version-restore lifecycle command requires target raw content: ${sourceMemo.id}"
                        }.rawContent
                    MemoLifecycleOperation.UPDATE,
                    MemoLifecycleOperation.DELETE_TO_TRASH,
                    MemoLifecycleOperation.RESTORE_FROM_TRASH,
                    MemoLifecycleOperation.PERMANENT_DELETE,
                    -> null
                },
        )
    }

    fun requireDurableIdentity(
        operationId: String,
        idempotencyKey: String,
    ) {
        val identity = metadata.outboxIdentity()
        require(operationId == identity.operationId && idempotencyKey == identity.idempotencyKey) {
            "Outbox durable lifecycle identity mismatch for ${sourceMemo.id}: " +
                "expected ${identity.operationId}/${identity.idempotencyKey}, " +
                "found $operationId/$idempotencyKey"
        }
    }

    companion object {
        fun createMemo(createdMemo: Memo): MemoLifecycleCommand {
            require(!createdMemo.isDeleted) {
                "Create command requires an active target memo: ${createdMemo.id}"
            }
            return create(MemoLifecycleOperation.CREATE, createdMemo)
        }

        fun updateMemo(
            sourceMemo: Memo,
            targetContent: String,
        ): MemoLifecycleCommand {
            require(!sourceMemo.isDeleted) {
                "Update command requires an active source memo: ${sourceMemo.id}"
            }
            require(targetContent.isNotBlank()) {
                "Update command requires non-blank target content: ${sourceMemo.id}"
            }
            return create(
                operation = MemoLifecycleOperation.UPDATE,
                sourceMemo = sourceMemo,
                targetContent = targetContent,
            )
        }

        fun deleteToTrash(sourceMemo: Memo): MemoLifecycleCommand {
            require(!sourceMemo.isDeleted) {
                "Delete-to-trash command requires an active source memo: ${sourceMemo.id}"
            }
            return create(MemoLifecycleOperation.DELETE_TO_TRASH, sourceMemo)
        }

        fun restoreFromTrash(sourceMemo: Memo): MemoLifecycleCommand {
            require(sourceMemo.isDeleted) {
                "Restore-from-trash command requires a trashed source memo: ${sourceMemo.id}"
            }
            return create(MemoLifecycleOperation.RESTORE_FROM_TRASH, sourceMemo)
        }

        fun permanentDelete(sourceMemo: Memo): MemoLifecycleCommand {
            require(sourceMemo.isDeleted) {
                "Permanent-delete command requires a trashed source memo: ${sourceMemo.id}"
            }
            return create(MemoLifecycleOperation.PERMANENT_DELETE, sourceMemo)
        }

        fun restoreRevision(
            currentMemo: Memo,
            currentRevisionId: String,
            targetRevisionId: String,
            targetLifecycleState: MemoRevisionLifecycleState,
            targetMemo: Memo,
            targetRawContent: String,
        ): MemoLifecycleCommand {
            require(currentMemo.id == targetMemo.id) {
                "Version-restore command requires matching memo ids: ${currentMemo.id}/${targetMemo.id}"
            }
            require(targetRevisionId.isNotBlank()) {
                "Version-restore command requires target revision id: ${currentMemo.id}"
            }
            require(currentRevisionId.isNotBlank()) {
                "Version-restore command requires current revision id: ${currentMemo.id}"
            }
            require(targetRawContent.isNotBlank()) {
                "Version-restore command requires target raw content: ${currentMemo.id}"
            }
            require(targetMemo.rawContent == targetRawContent) {
                "Version-restore command target memo must carry target raw content: ${currentMemo.id}"
            }
            requireSafeMemoDateKey(currentMemo.dateKey)
            requireSafeMemoDateKey(targetMemo.dateKey)
            val identity =
                MemoFileOutboxIdentityPolicy.forVersionRestoreHandoff(
                    memoId = currentMemo.id,
                    currentRevisionId = currentRevisionId,
                    currentRevisionHash = currentMemo.rawContent.toVersionHash(),
                    targetRevisionId = targetRevisionId,
                    targetRevisionHash = targetRawContent.toVersionHash(),
                )
            return MemoLifecycleCommand(
                operation = MemoLifecycleOperation.VERSION_RESTORE,
                sourceMemo = currentMemo,
                metadata =
                    MemoLifecycleCommandMetadata(
                        operationId = MemoLifecycleOperationId(identity.operationId),
                        idempotencyKey = MemoLifecycleIdempotencyKey(identity.idempotencyKey),
                        sourceRevisionHash = currentMemo.rawContent.toVersionHash(),
                        targetRevisionHash = targetRawContent.toVersionHash(),
                        sourceRevisionId = currentRevisionId,
                        targetRevisionId = targetRevisionId,
                    ),
                revisionRestoreTarget =
                    MemoRevisionRestoreTarget(
                        revisionId = targetRevisionId,
                        lifecycleState = targetLifecycleState,
                        memo = targetMemo.copy(isDeleted = targetLifecycleState != MemoRevisionLifecycleState.ACTIVE),
                        rawContent = targetRawContent,
                    ),
            )
        }

        private fun create(
            operation: MemoLifecycleOperation,
            sourceMemo: Memo,
            targetContent: String? = null,
        ): MemoLifecycleCommand {
            requireSafeMemoDateKey(sourceMemo.dateKey)
            val identity =
                when (operation) {
                    MemoLifecycleOperation.CREATE ->
                        MemoFileOutboxIdentityPolicy.forCreate(
                            memoId = sourceMemo.id,
                            memoDate = sourceMemo.dateKey,
                            createRawContent = sourceMemo.rawContent,
                        )
                    MemoLifecycleOperation.UPDATE ->
                        MemoFileOutboxIdentityPolicy.forUpdate(
                            memoId = sourceMemo.id,
                            memoDate = sourceMemo.dateKey,
                            memoRawContent = sourceMemo.rawContent,
                            newContent =
                                requireNotNull(targetContent) {
                                    "Update lifecycle identity requires target content: ${sourceMemo.id}"
                                },
                        )
                    MemoLifecycleOperation.DELETE_TO_TRASH ->
                        MemoFileOutboxIdentityPolicy.forDeleteToTrash(
                            memoId = sourceMemo.id,
                            memoDate = sourceMemo.dateKey,
                            memoRawContent = sourceMemo.rawContent,
                        )
                    MemoLifecycleOperation.RESTORE_FROM_TRASH ->
                        MemoFileOutboxIdentityPolicy.forRestoreFromTrash(
                            memoId = sourceMemo.id,
                            memoDate = sourceMemo.dateKey,
                            memoRawContent = sourceMemo.rawContent,
                        )
                    MemoLifecycleOperation.PERMANENT_DELETE ->
                        MemoFileOutboxIdentityPolicy.forPermanentDelete(
                            memoId = sourceMemo.id,
                            memoDate = sourceMemo.dateKey,
                            trashedRawContent = sourceMemo.rawContent,
                        )
                    MemoLifecycleOperation.VERSION_RESTORE ->
                        error("Version-restore lifecycle identity requires target revision metadata: ${sourceMemo.id}")
                }
            return MemoLifecycleCommand(
                operation = operation,
                sourceMemo = sourceMemo,
                metadata =
                    MemoLifecycleCommandMetadata(
                        operationId = MemoLifecycleOperationId(identity.operationId),
                        idempotencyKey = MemoLifecycleIdempotencyKey(identity.idempotencyKey),
                        sourceRevisionHash = sourceMemo.rawContent.toVersionHash(),
                        targetRevisionHash = targetContent?.toVersionHash(),
                    ),
                targetContent = targetContent,
            )
        }
    }
}

@Serializable
private data class MemoRevisionRestoreOutboxPayload(
    val currentRevisionId: String,
    val targetRevisionId: String,
    val lifecycleState: String,
    val sourceDateKey: String,
    val sourceTimestamp: Long,
    val sourceContent: String,
    val sourceDeleted: Boolean,
    val targetUpdatedAt: Long,
)

private val memoRevisionRestoreOutboxJson = Json {
    encodeDefaults = true
}

private fun MemoRevisionRestoreTarget.toOutboxPayload(
    sourceMemo: Memo,
    metadata: MemoLifecycleCommandMetadata,
): String =
    memoRevisionRestoreOutboxJson.encodeToString(
        MemoRevisionRestoreOutboxPayload(
            currentRevisionId =
                requireNotNull(metadata.sourceRevisionId) {
                    "Version-restore outbox payload requires current revision id: ${sourceMemo.id}"
                },
            targetRevisionId = revisionId,
            lifecycleState = lifecycleState.name,
            sourceDateKey = sourceMemo.dateKey,
            sourceTimestamp = sourceMemo.timestamp,
            sourceContent = sourceMemo.content,
            sourceDeleted = sourceMemo.isDeleted,
            targetUpdatedAt = memo.updatedAt,
        ),
    )

internal fun decodeRevisionRestoreOutboxCommand(item: MemoFileOutboxEntity): MemoLifecycleCommand {
    require(item.operation == MemoFileOutboxOp.VERSION_RESTORE) {
        "Revision-restore outbox command requires VERSION_RESTORE operation for memo ${item.memoId}"
    }
    val payload =
        memoRevisionRestoreOutboxJson.decodeFromString<MemoRevisionRestoreOutboxPayload>(
            requireNotNull(item.newContent) { "VERSION_RESTORE outbox requires payload for memo ${item.memoId}" },
        )
    val targetRawContent =
        requireNotNull(item.createRawContent) {
            "VERSION_RESTORE outbox requires target raw content for memo ${item.memoId}"
        }
    val targetContent = contentFromRawMemoSource(targetRawContent)
    val targetMemo =
        Memo(
            id = item.memoId,
            timestamp = item.memoTimestamp,
            updatedAt = payload.targetUpdatedAt,
            content = targetContent,
            rawContent = targetRawContent,
            dateKey = item.memoDate,
            isDeleted = payload.lifecycleState != MemoRevisionLifecycleState.ACTIVE.name,
        )
    val sourceMemo =
        Memo(
            id = item.memoId,
            timestamp = payload.sourceTimestamp,
            content = payload.sourceContent,
            rawContent = item.memoRawContent,
            dateKey = payload.sourceDateKey,
            isDeleted = payload.sourceDeleted,
        )
    return MemoLifecycleCommand.restoreRevision(
        currentMemo = sourceMemo,
        currentRevisionId = payload.currentRevisionId,
        targetRevisionId = payload.targetRevisionId,
        targetLifecycleState = MemoRevisionLifecycleState.valueOf(payload.lifecycleState),
        targetMemo = targetMemo,
        targetRawContent = targetRawContent,
    ).also { command ->
        command.requireDurableIdentity(
            operationId = item.operationId,
            idempotencyKey = item.idempotencyKey,
        )
    }
}

private fun MemoLifecycleCommandMetadata.outboxIdentity() =
    com.lomo.data.local.entity.MemoFileOutboxIdentity(
        operationId = operationId.value,
        idempotencyKey = idempotencyKey.value,
    )
