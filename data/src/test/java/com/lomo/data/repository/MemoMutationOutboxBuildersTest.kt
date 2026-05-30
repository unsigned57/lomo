package com.lomo.data.repository

import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxIdentityKind
import com.lomo.data.local.entity.MemoFileOutboxIdentityPolicy
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionLifecycleState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain

/*
 * Behavior Contract:
 * - Unit under test: MemoMutationOutboxBuilders.
 * - Owning layer: data repository lifecycle mutation pipeline.
 * - Priority tier: P0.
 * - Capability: memo-to-outbox mapping keeps lifecycle operation identity durable and
 *   validates outbox-to-command reconstruction on retry.
 *
 * Scenarios:
 * - Given update/create inputs, when outbox rows are built, then memo identity, content, and
 *   file-apply payload are persisted.
 * - Given create/update outbox rows survive process restart, when retry reconstructs the command,
 *   then the durable identity is verified before file apply can run.
 * - Given command-owned delete/restore inputs, when outbox rows are built, then operation id and
 *   idempotency key are persisted with the row.
 * - Given command-owned permanent-delete inputs, when outbox rows are built and later retried,
 *   then the durable deleted memo identity reconstructs without caller state.
 * - Given supported lifecycle variants, when their outbox identity is requested, then each variant
 *   has a stable idempotency namespace and clear-trash has no batch-level identity namespace.
 * - Given a version-restore lifecycle command, when its outbox row is persisted and later rebuilt,
 *   then the target revision payload, source memo state, and durable identity all round-trip.
 * - Given a lifecycle outbox row whose payload no longer matches durable identity, when retry
 *   reconstructs the command, then the row is rejected instead of silently creating a new identity.
 * - Given malformed restore rows, when source memo reconstruction runs, then missing content is surfaced.
 *
 * Observable outcomes:
 * - operation type, mapped identifiers/date or timestamp/raw content fields, new-content handling,
 *   durable operation identity fields, reconstructed memo content/deleted state, and malformed row
 *   rejection.
 *
 * TDD proof:
 * - Fails before the fix because lifecycle outbox rows do not persist operation id/idempotency key
 *   and retry reconstructs command identity from payload alone.
 *
 * Excludes:
 * - outbox DAO persistence behavior, mutation scheduling, and file-system side effects.
 */
class MemoMutationOutboxBuildersTest : DataFunSpec() {
    init {
        test("buildUpdateOutbox maps update operation and new content fields") {
            `buildUpdateOutbox maps update operation and new content fields`()
        }

        test("create outbox row reconstructs command with durable identity") {
            `create outbox row reconstructs command with durable identity`()
        }

        test("update outbox row reconstructs command with durable identity") {
            `update outbox row reconstructs command with durable identity`()
        }

        test("toLifecycleCommand rejects update rows whose durable identity does not match payload") {
            `toLifecycleCommand rejects update rows whose durable identity does not match payload`()
        }

        test("buildDeleteOutbox maps delete lifecycle command with empty new content") {
            `buildDeleteOutbox maps delete lifecycle command with empty new content`()
        }

        test("buildRestoreOutbox maps restore lifecycle command with source memo content") {
            `buildRestoreOutbox maps restore lifecycle command with source memo content`()
        }

        test("buildPermanentDeleteOutbox maps permanent delete lifecycle command with deleted source state") {
            `buildPermanentDeleteOutbox maps permanent delete lifecycle command with deleted source state`()
        }

        test("outboxSourceMemo reconstructs source-facing memo fields") {
            `outboxSourceMemo reconstructs source-facing memo fields`()
        }

        test("outboxSourceMemo reconstructs delete content from raw source") {
            `outboxSourceMemo reconstructs delete content from raw source`()
        }

        test("outboxSourceMemo rejects restore rows without source content") {
            `outboxSourceMemo rejects restore rows without source content`()
        }

        test("outbox identity policy defines namespaces for supported lifecycle variants") {
            `outbox identity policy defines namespaces for supported lifecycle variants`()
        }

        test("version restore handoff identity is deterministic from durable revision inputs") {
            `version restore handoff identity is deterministic from durable revision inputs`()
        }

        test("version restore outbox row reconstructs command with target revision payload") {
            `version restore outbox row reconstructs command with target revision payload`()
        }

        test("toLifecycleCommand rejects rows whose durable identity does not match payload") {
            `toLifecycleCommand rejects rows whose durable identity does not match payload`()
        }
    }


    private val sourceMemo =
        Memo(
            id = "memo_1",
            timestamp = 1_711_500_100_000L,
            updatedAt = 1_711_500_200_000L,
            content = "source content",
            rawContent = "- 09:15 source content",
            dateKey = "2026_03_27",
        )

    private fun `buildUpdateOutbox maps update operation and new content fields`() {
        val result = buildUpdateOutbox(sourceMemo = sourceMemo, newContent = "updated content")

        result.operation shouldBe MemoFileOutboxOp.UPDATE
        result.memoId shouldBe sourceMemo.id
        result.memoDate shouldBe sourceMemo.dateKey
        result.memoTimestamp shouldBe sourceMemo.timestamp
        result.memoRawContent shouldBe sourceMemo.rawContent
        result.newContent shouldBe "updated content"
        result.createRawContent.shouldBeNull()
        val expectedIdentity =
            MemoFileOutboxIdentityPolicy.forUpdate(
                memoId = sourceMemo.id,
                memoDate = sourceMemo.dateKey,
                memoRawContent = sourceMemo.rawContent,
                newContent = "updated content",
            )
        result.operationId shouldBe expectedIdentity.operationId
        result.idempotencyKey shouldBe expectedIdentity.idempotencyKey
    }

    private fun `create outbox row reconstructs command with durable identity`() {
        val outbox =
            MemoFileOutboxEntity(
                operation = MemoFileOutboxOp.CREATE,
                operationId =
                    MemoFileOutboxIdentityPolicy
                        .forCreate(
                            memoId = sourceMemo.id,
                            memoDate = sourceMemo.dateKey,
                            createRawContent = sourceMemo.rawContent,
                        ).operationId,
                idempotencyKey =
                    MemoFileOutboxIdentityPolicy
                        .forCreate(
                            memoId = sourceMemo.id,
                            memoDate = sourceMemo.dateKey,
                            createRawContent = sourceMemo.rawContent,
                        ).idempotencyKey,
                memoId = sourceMemo.id,
                memoDate = sourceMemo.dateKey,
                memoTimestamp = sourceMemo.timestamp,
                memoRawContent = sourceMemo.rawContent,
                newContent = sourceMemo.content,
                createRawContent = sourceMemo.rawContent,
            )

        val command = outbox.toLifecycleCommand()

        command.operation.outboxOperation shouldBe MemoFileOutboxOp.CREATE
        command.metadata.operationId.value shouldBe outbox.operationId
        command.metadata.idempotencyKey.value shouldBe outbox.idempotencyKey
        command.sourceMemo.id shouldBe sourceMemo.id
        command.sourceMemo.timestamp shouldBe sourceMemo.timestamp
        command.sourceMemo.content shouldBe sourceMemo.content
        command.sourceMemo.rawContent shouldBe sourceMemo.rawContent
        command.sourceMemo.dateKey shouldBe sourceMemo.dateKey
    }

    private fun `update outbox row reconstructs command with durable identity`() {
        val outbox = buildUpdateOutbox(sourceMemo = sourceMemo, newContent = "updated content")

        val command = outbox.toLifecycleCommand()

        command.operation.outboxOperation shouldBe MemoFileOutboxOp.UPDATE
        command.metadata.operationId.value shouldBe outbox.operationId
        command.metadata.idempotencyKey.value shouldBe outbox.idempotencyKey
        command.sourceMemo.id shouldBe sourceMemo.id
        command.sourceMemo.timestamp shouldBe sourceMemo.timestamp
        command.sourceMemo.content shouldBe sourceMemo.content
        command.sourceMemo.rawContent shouldBe sourceMemo.rawContent
        command.sourceMemo.dateKey shouldBe sourceMemo.dateKey
        command.targetContent shouldBe "updated content"
    }

    private fun `toLifecycleCommand rejects update rows whose durable identity does not match payload`() {
        val outbox =
            buildUpdateOutbox(sourceMemo = sourceMemo, newContent = "updated content")
                .copy(
                    operationId =
                        "memo-lifecycle:update:" +
                            "update:${sourceMemo.id}:wrong-date:wrong-source:wrong-target",
                    idempotencyKey = "update:${sourceMemo.id}:wrong-date:wrong-source:wrong-target",
                )

        shouldThrow<IllegalArgumentException> {
            outbox.toLifecycleCommand()
        }.message shouldContain "Outbox durable lifecycle identity mismatch"
    }

    private fun `buildDeleteOutbox maps delete lifecycle command with empty new content`() {
        val command = MemoLifecycleCommand.deleteToTrash(sourceMemo)

        val result = buildDeleteOutbox(command = command)

        result.operation shouldBe MemoFileOutboxOp.DELETE
        result.memoId shouldBe sourceMemo.id
        result.memoDate shouldBe sourceMemo.dateKey
        result.memoTimestamp shouldBe sourceMemo.timestamp
        result.memoRawContent shouldBe sourceMemo.rawContent
        result.newContent.shouldBeNull()
        result.createRawContent.shouldBeNull()
        result.operationId shouldBe command.metadata.operationId.value
        result.idempotencyKey shouldBe command.metadata.idempotencyKey.value
    }

    private fun `buildRestoreOutbox maps restore lifecycle command with source memo content`() {
        val command = MemoLifecycleCommand.restoreFromTrash(sourceMemo.copy(isDeleted = true))

        val result = buildRestoreOutbox(command = command)

        result.operation shouldBe MemoFileOutboxOp.RESTORE
        result.memoId shouldBe sourceMemo.id
        result.memoDate shouldBe sourceMemo.dateKey
        result.memoTimestamp shouldBe sourceMemo.timestamp
        result.memoRawContent shouldBe sourceMemo.rawContent
        result.newContent shouldBe sourceMemo.content
        result.createRawContent.shouldBeNull()
        result.operationId shouldBe command.metadata.operationId.value
        result.idempotencyKey shouldBe command.metadata.idempotencyKey.value
    }

    private fun `buildPermanentDeleteOutbox maps permanent delete lifecycle command with deleted source state`() {
        val command = MemoLifecycleCommand.permanentDelete(sourceMemo.copy(isDeleted = true))

        val result = buildPermanentDeleteOutbox(command = command)
        val rebuilt = result.toLifecycleCommand()

        result.operation shouldBe MemoFileOutboxOp.PERMANENT_DELETE
        result.memoId shouldBe sourceMemo.id
        result.memoDate shouldBe sourceMemo.dateKey
        result.memoTimestamp shouldBe sourceMemo.timestamp
        result.memoRawContent shouldBe sourceMemo.rawContent
        result.newContent.shouldBeNull()
        result.createRawContent.shouldBeNull()
        result.operationId shouldBe command.metadata.operationId.value
        result.idempotencyKey shouldBe command.metadata.idempotencyKey.value
        rebuilt.operation shouldBe MemoLifecycleOperation.PERMANENT_DELETE
        rebuilt.sourceMemo.isDeleted shouldBe true
        rebuilt.metadata.operationId shouldBe command.metadata.operationId
        rebuilt.metadata.idempotencyKey shouldBe command.metadata.idempotencyKey
    }

    private fun `outboxSourceMemo reconstructs source-facing memo fields`() {
        val identity =
            MemoFileOutboxIdentityPolicy.forUpdate(
                memoId = "memo_2",
                memoDate = "2026_03_28",
                memoRawContent = "- 10:30 rebuilt",
                newContent = "rebuilt",
            )
        val outbox =
            MemoFileOutboxEntity(
                operation = MemoFileOutboxOp.UPDATE,
                operationId = identity.operationId,
                idempotencyKey = identity.idempotencyKey,
                memoId = "memo_2",
                memoDate = "2026_03_28",
                memoTimestamp = 1_711_600_000_000L,
                memoRawContent = "- 10:30 rebuilt",
                newContent = "rebuilt",
                createRawContent = null,
            )

        val memo = outboxSourceMemo(outbox)

        memo.id shouldBe "memo_2"
        memo.timestamp shouldBe 1_711_600_000_000L
        memo.content shouldBe "rebuilt"
        memo.rawContent shouldBe "- 10:30 rebuilt"
        memo.dateKey shouldBe "2026_03_28"
        memo.isDeleted shouldBe false
    }

    private fun `outboxSourceMemo reconstructs delete content from raw source`() {
        val identity =
            MemoFileOutboxIdentityPolicy.forDeleteToTrash(
                memoId = "memo_3",
                memoDate = "2026_03_29",
                memoRawContent = "- 11:00 deleted",
            )
        val outbox =
            MemoFileOutboxEntity(
                operation = MemoFileOutboxOp.DELETE,
                operationId = identity.operationId,
                idempotencyKey = identity.idempotencyKey,
                memoId = "memo_3",
                memoDate = "2026_03_29",
                memoTimestamp = 1_711_700_000_000L,
                memoRawContent = "- 11:00 deleted",
                newContent = null,
                createRawContent = null,
            )

        val memo = outboxSourceMemo(outbox)

        memo.content shouldBe "deleted"
        memo.rawContent shouldBe "- 11:00 deleted"
        memo.isDeleted shouldBe false
    }

    private fun `outboxSourceMemo rejects restore rows without source content`() {
        val identity =
            MemoFileOutboxIdentityPolicy.forRestoreFromTrash(
                memoId = "memo_4",
                memoDate = "2026_03_30",
                memoRawContent = "- 12:00 restore",
            )
        val outbox =
            MemoFileOutboxEntity(
                operation = MemoFileOutboxOp.RESTORE,
                operationId = identity.operationId,
                idempotencyKey = identity.idempotencyKey,
                memoId = "memo_4",
                memoDate = "2026_03_30",
                memoTimestamp = 1_711_800_000_000L,
                memoRawContent = "- 12:00 restore",
                newContent = null,
                createRawContent = null,
            )

        shouldThrow<IllegalArgumentException> {
            outboxSourceMemo(outbox)
        }
    }

    private fun `outbox identity policy defines namespaces for supported lifecycle variants`() {
        val create =
            MemoFileOutboxIdentityPolicy.forCreate(
                memoId = sourceMemo.id,
                memoDate = sourceMemo.dateKey,
                createRawContent = sourceMemo.rawContent,
            )
        val update =
            MemoFileOutboxIdentityPolicy.forUpdate(
                memoId = sourceMemo.id,
                memoDate = sourceMemo.dateKey,
                memoRawContent = sourceMemo.rawContent,
                newContent = "updated content",
            )
        val delete =
            MemoFileOutboxIdentityPolicy.forDeleteToTrash(
                memoId = sourceMemo.id,
                memoDate = sourceMemo.dateKey,
                memoRawContent = sourceMemo.rawContent,
            )
        val restore =
            MemoFileOutboxIdentityPolicy.forRestoreFromTrash(
                memoId = sourceMemo.id,
                memoDate = sourceMemo.dateKey,
                memoRawContent = sourceMemo.rawContent,
            )
        val permanentDelete =
            MemoFileOutboxIdentityPolicy.forPermanentDelete(
                memoId = sourceMemo.id,
                memoDate = sourceMemo.dateKey,
                trashedRawContent = sourceMemo.rawContent,
            )
        val versionRestore =
            MemoFileOutboxIdentityPolicy.forVersionRestoreHandoff(
                memoId = sourceMemo.id,
                currentRevisionId = "rev-current",
                currentRevisionHash = sourceMemo.rawContent.toVersionHash(),
                targetRevisionId = "rev-target",
                targetRevisionHash = "target raw content".toVersionHash(),
            )

        create.idempotencyKey.startsWith("create:") shouldBe true
        update.idempotencyKey.startsWith("update:") shouldBe true
        delete.idempotencyKey.startsWith("delete-to-trash:") shouldBe true
        restore.idempotencyKey.startsWith("restore-from-trash:") shouldBe true
        permanentDelete.idempotencyKey.startsWith("permanent-delete:") shouldBe true
        versionRestore.idempotencyKey.startsWith("version-restore:") shouldBe true
        MemoFileOutboxIdentityKind.entries.map { kind -> kind.slug } shouldBe
            listOf(
                "create",
                "update",
                "delete-to-trash",
                "restore-from-trash",
                "permanent-delete",
                "version-restore",
            )
        listOf(create, update, delete, restore, permanentDelete, versionRestore)
            .map { identity -> identity.idempotencyKey }
            .toSet()
            .size shouldBe 6
    }

    private fun `version restore handoff identity is deterministic from durable revision inputs`() {
        val first =
            MemoFileOutboxIdentityPolicy.forVersionRestoreHandoff(
                memoId = sourceMemo.id,
                currentRevisionId = "rev-current",
                currentRevisionHash = sourceMemo.rawContent.toVersionHash(),
                targetRevisionId = "rev-target",
                targetRevisionHash = "target raw content".toVersionHash(),
            )
        val rebuilt =
            MemoFileOutboxIdentityPolicy.forVersionRestoreHandoff(
                memoId = sourceMemo.id,
                currentRevisionId = "rev-current",
                currentRevisionHash = sourceMemo.rawContent.toVersionHash(),
                targetRevisionId = "rev-target",
                targetRevisionHash = "target raw content".toVersionHash(),
            )
        val differentTarget =
            MemoFileOutboxIdentityPolicy.forVersionRestoreHandoff(
                memoId = sourceMemo.id,
                currentRevisionId = "rev-current",
                currentRevisionHash = sourceMemo.rawContent.toVersionHash(),
                targetRevisionId = "rev-other-target",
                targetRevisionHash = "other target raw content".toVersionHash(),
            )

        rebuilt shouldBe first
        first.operationId shouldBe "memo-lifecycle:version-restore:${first.idempotencyKey}"
        first.idempotencyKey shouldBe
            "version-restore:${sourceMemo.id}:rev-current:${sourceMemo.rawContent.toVersionHash()}:" +
            "rev-target:${"target raw content".toVersionHash()}"
        (differentTarget.idempotencyKey == first.idempotencyKey) shouldBe false
    }

    private fun `version restore outbox row reconstructs command with target revision payload`() {
        val targetMemo =
            sourceMemo.copy(
                timestamp = 1_711_586_400_000L,
                updatedAt = 1_711_586_500_000L,
                content = "restored target",
                rawContent = "- 10:45 restored target",
                dateKey = "2026_03_28",
            )
        val command =
            MemoLifecycleCommand.restoreRevision(
                currentMemo = sourceMemo,
                currentRevisionId = "revision-current",
                targetRevisionId = "revision-target",
                targetLifecycleState = MemoRevisionLifecycleState.TRASHED,
                targetMemo = targetMemo,
                targetRawContent = targetMemo.rawContent,
            )

        val outbox = buildVersionRestoreOutbox(command)
        val rebuilt = outbox.toLifecycleCommand()
        val rebuiltTarget = rebuilt.revisionRestoreTarget.shouldNotBeNull()

        outbox.operation shouldBe MemoFileOutboxOp.VERSION_RESTORE
        outbox.memoId shouldBe sourceMemo.id
        outbox.memoDate shouldBe targetMemo.dateKey
        outbox.memoTimestamp shouldBe targetMemo.timestamp
        outbox.memoRawContent shouldBe sourceMemo.rawContent
        outbox.createRawContent shouldBe targetMemo.rawContent
        outbox.operationId shouldBe command.metadata.operationId.value
        outbox.idempotencyKey shouldBe command.metadata.idempotencyKey.value
        rebuilt.operation shouldBe MemoLifecycleOperation.VERSION_RESTORE
        rebuilt.sourceMemo.id shouldBe sourceMemo.id
        rebuilt.sourceMemo.content shouldBe sourceMemo.content
        rebuilt.sourceMemo.rawContent shouldBe sourceMemo.rawContent
        rebuilt.sourceMemo.dateKey shouldBe sourceMemo.dateKey
        rebuilt.metadata.operationId shouldBe command.metadata.operationId
        rebuilt.metadata.idempotencyKey shouldBe command.metadata.idempotencyKey
        rebuiltTarget.revisionId shouldBe "revision-target"
        rebuiltTarget.lifecycleState shouldBe MemoRevisionLifecycleState.TRASHED
        rebuiltTarget.rawContent shouldBe targetMemo.rawContent
        rebuiltTarget.memo.id shouldBe targetMemo.id
        rebuiltTarget.memo.content shouldBe targetMemo.content
        rebuiltTarget.memo.rawContent shouldBe targetMemo.rawContent
        rebuiltTarget.memo.dateKey shouldBe targetMemo.dateKey
        rebuiltTarget.memo.isDeleted shouldBe true
    }

    private fun `toLifecycleCommand rejects rows whose durable identity does not match payload`() {
        val command = MemoLifecycleCommand.deleteToTrash(sourceMemo)
        val outbox =
            command
                .toOutboxEntity()
                .copy(
                    operationId =
                        "memo-lifecycle:delete-to-trash:" +
                            "delete-to-trash:${sourceMemo.id}:wrong-date:wrong-hash",
                    idempotencyKey = "delete-to-trash:${sourceMemo.id}:wrong-date:wrong-hash",
                )

        shouldThrow<IllegalArgumentException> {
            outbox.toLifecycleCommand()
        }.message shouldContain "Outbox durable lifecycle identity mismatch"
    }
}
