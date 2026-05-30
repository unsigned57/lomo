package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeFileDataSource
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MemoLifecycleCommand delete/restore/permanent-delete lifecycle slice.
 * - Owning layer: data repository lifecycle mutation pipeline.
 * - Priority tier: P0.
 * - Capability: delete/restore mutations carry one canonical operation and idempotency contract
 *   before DB, file, outbox, version, and remote-record handoff code consumes them.
 *
 * Scenarios:
 * - Given the same active memo, when a delete lifecycle command is rebuilt, then operation id,
 *   idempotency key, outbox metadata, and version metadata remain stable and command-derived.
 * - Given the same trashed memo, when a restore lifecycle command is built, then restore outbox
 *   and version metadata differ from delete while retaining the same memo revision identity.
 * - Given the same trashed memo, when a permanent-delete lifecycle command is built, then outbox
 *   and version metadata use the durable permanent-delete identity and deleted revision state.
 * - Given a lifecycle command outbox row survives process restart, when retry rebuilds the command,
 *   then the durable row exposes the same operation id and idempotency key without caller state.
 * - Given a delete outbox retry sees raw text only as a substring of another trash memo block,
 *   when the trash finisher runs, then it appends the exact source block instead of treating the
 *   substring as an idempotent match.
 *
 * Observable outcomes:
 * - operation id strings, idempotency key strings, MemoFileOutboxEntity fields, version
 *   lifecycle/origin metadata, and persisted trash file/local-file-state contents.
 *
 * TDD proof:
 * - Fails before the fix because delete/restore outbox builders accept bare Memo values without
 *   command metadata, and trash retry idempotency uses raw substring matching.
 *
 * Excludes:
 * - Room schema migration, version blob persistence, app/UI callers, and remote transport upload.
 */
class MemoLifecycleCommandTest : DataFunSpec() {
    init {
        test("given active memo when delete command is rebuilt then metadata and handoff rows are stable") {
            givenActiveMemoWhenDeleteCommandIsRebuiltThenMetadataAndHandoffRowsAreStable()
        }

        test("given trashed memo when restore command is built then restore handoff metadata is explicit") {
            givenTrashedMemoWhenRestoreCommandIsBuiltThenRestoreHandoffMetadataIsExplicit()
        }

        test("given trashed memo when permanent delete command is built then deleted handoff metadata is explicit") {
            givenTrashedMemoWhenPermanentDeleteCommandIsBuiltThenDeletedHandoffMetadataIsExplicit()
        }

        test("given persisted lifecycle outbox row when command is rebuilt then durable identity round trips") {
            givenPersistedLifecycleOutboxRowWhenCommandIsRebuiltThenDurableIdentityRoundTrips()
        }

        test("given raw content substring in trash when delete retry finishes then exact block is appended") {
            givenRawContentSubstringInTrashWhenDeleteRetryFinishesThenExactBlockIsAppended()
        }
    }

    private fun givenActiveMemoWhenDeleteCommandIsRebuiltThenMetadataAndHandoffRowsAreStable() {
        val memo = lifecycleMemo(content = "delete me", rawContent = "- 10:00 delete me")

        val command = MemoLifecycleCommand.deleteToTrash(memo)
        val rebuilt = MemoLifecycleCommand.deleteToTrash(memo)

        command.metadata.operationId shouldBe rebuilt.metadata.operationId
        command.metadata.idempotencyKey shouldBe rebuilt.metadata.idempotencyKey
        command.metadata.sourceRevisionHash shouldBe memo.rawContent.toVersionHash()
        command.metadata.operationId.value shouldBe
            "memo-lifecycle:delete-to-trash:${command.metadata.idempotencyKey.value}"
        command.metadata.idempotencyKey.value shouldBe
            "delete-to-trash:${memo.id}:${memo.dateKey}:${memo.rawContent.toVersionHash()}"
        command.operation.outboxOperation shouldBe MemoFileOutboxOp.DELETE
        command.operation.versionLifecycleState shouldBe MemoRevisionLifecycleState.TRASHED
        command.operation.versionOrigin shouldBe MemoRevisionOrigin.LOCAL_TRASH
        command.versionMemo.isDeleted shouldBe true

        val outbox = command.toOutboxEntity()

        outbox.operation shouldBe MemoFileOutboxOp.DELETE
        outbox.memoId shouldBe memo.id
        outbox.memoDate shouldBe memo.dateKey
        outbox.memoTimestamp shouldBe memo.timestamp
        outbox.memoRawContent shouldBe memo.rawContent
        outbox.newContent shouldBe null
        outbox.createRawContent shouldBe null
    }

    private fun givenTrashedMemoWhenRestoreCommandIsBuiltThenRestoreHandoffMetadataIsExplicit() {
        val memo =
            lifecycleMemo(
                content = "restore me",
                rawContent = "- 10:00 restore me",
                isDeleted = true,
            )

        val restoreCommand = MemoLifecycleCommand.restoreFromTrash(memo)
        val deleteCommand = MemoLifecycleCommand.deleteToTrash(memo.copy(isDeleted = false))

        restoreCommand.metadata.operationId.value shouldBe
            "memo-lifecycle:restore-from-trash:${restoreCommand.metadata.idempotencyKey.value}"
        restoreCommand.metadata.idempotencyKey.value shouldBe
            "restore-from-trash:${memo.id}:${memo.dateKey}:${memo.rawContent.toVersionHash()}"
        (restoreCommand.metadata.operationId == deleteCommand.metadata.operationId) shouldBe false
        restoreCommand.operation.outboxOperation shouldBe MemoFileOutboxOp.RESTORE
        restoreCommand.operation.versionLifecycleState shouldBe MemoRevisionLifecycleState.ACTIVE
        restoreCommand.operation.versionOrigin shouldBe MemoRevisionOrigin.LOCAL_RESTORE
        restoreCommand.versionMemo.isDeleted shouldBe false

        val outbox = restoreCommand.toOutboxEntity()

        outbox.operation shouldBe MemoFileOutboxOp.RESTORE
        outbox.memoId shouldBe memo.id
        outbox.memoDate shouldBe memo.dateKey
        outbox.memoTimestamp shouldBe memo.timestamp
        outbox.memoRawContent shouldBe memo.rawContent
        outbox.newContent shouldBe memo.content
        outbox.createRawContent shouldBe null
    }

    private fun givenTrashedMemoWhenPermanentDeleteCommandIsBuiltThenDeletedHandoffMetadataIsExplicit() {
        val memo =
            lifecycleMemo(
                content = "destroy me",
                rawContent = "- 10:00 destroy me",
                isDeleted = true,
            )

        val command = MemoLifecycleCommand.permanentDelete(memo)
        val rebuilt = MemoLifecycleCommand.permanentDelete(memo)

        command.metadata.operationId shouldBe rebuilt.metadata.operationId
        command.metadata.idempotencyKey shouldBe rebuilt.metadata.idempotencyKey
        command.metadata.operationId.value shouldBe
            "memo-lifecycle:permanent-delete:${command.metadata.idempotencyKey.value}"
        command.metadata.idempotencyKey.value shouldBe
            "permanent-delete:${memo.id}:${memo.dateKey}:${memo.rawContent.toVersionHash()}"
        command.operation.outboxOperation shouldBe MemoFileOutboxOp.PERMANENT_DELETE
        command.operation.versionLifecycleState shouldBe MemoRevisionLifecycleState.DELETED
        command.operation.versionOrigin shouldBe MemoRevisionOrigin.LOCAL_DELETE
        command.versionMemo.isDeleted shouldBe true

        val outbox = command.toOutboxEntity()

        outbox.operation shouldBe MemoFileOutboxOp.PERMANENT_DELETE
        outbox.memoId shouldBe memo.id
        outbox.memoDate shouldBe memo.dateKey
        outbox.memoTimestamp shouldBe memo.timestamp
        outbox.memoRawContent shouldBe memo.rawContent
        outbox.newContent shouldBe null
        outbox.createRawContent shouldBe null
    }

    private fun givenPersistedLifecycleOutboxRowWhenCommandIsRebuiltThenDurableIdentityRoundTrips() {
        val memo = lifecycleMemo(content = "durable identity", rawContent = "- 10:00 durable identity")
        val original = MemoLifecycleCommand.deleteToTrash(memo)
        val persistedRow =
            original
                .toOutboxEntity()
                .copy(id = 42L, retryCount = 1, claimToken = "previous-process")

        val rebuilt = persistedRow.toLifecycleCommand()

        persistedRow.operationId shouldBe original.metadata.operationId.value
        persistedRow.idempotencyKey shouldBe original.metadata.idempotencyKey.value
        rebuilt.metadata.operationId shouldBe original.metadata.operationId
        rebuilt.metadata.idempotencyKey shouldBe original.metadata.idempotencyKey
    }

    private fun givenRawContentSubstringInTrashWhenDeleteRetryFinishesThenExactBlockIsAppended() =
        runTest {
            val memo = lifecycleMemo(content = "target memo", rawContent = "- 10:00 target memo")
            val command = MemoLifecycleCommand.deleteToTrash(memo)
            val filename = "${memo.dateKey}.md"
            val fileDataSource = FakeFileDataSource()
            val localFileStateDao = InMemoryLocalFileStateDao()
            fileDataSource.files[MemoDirectoryType.TRASH to filename] = "- 10:00 target memo with suffix"

            val result =
                testMemoWorkspaceStore(
                    markdownStorageDataSource = fileDataSource,
                    localFileStateDao = localFileStateDao,
                ).ensureTrashMemoBlock(command.sourceMemo)

            result shouldBe MemoWorkspaceBlockMutationResult.Applied
            fileDataSource.files[MemoDirectoryType.TRASH to filename] shouldBe
                "- 10:00 target memo with suffix\n- 10:00 target memo\n"
            localFileStateDao.getByFilename(filename, isTrash = true).shouldNotBeNull()
        }

    private fun lifecycleMemo(
        content: String,
        rawContent: String,
        isDeleted: Boolean = false,
    ): Memo =
        Memo(
            id = "memo_2026_05_25_10:00_target",
            timestamp = 1_795_478_400_000L,
            updatedAt = 1_795_478_400_000L,
            content = content,
            rawContent = rawContent,
            dateKey = "2026_05_25",
            isDeleted = isDeleted,
        )
}

private class InMemoryLocalFileStateDao : LocalFileStateDao {
    private val states = linkedMapOf<Pair<String, Boolean>, LocalFileStateEntity>()

    override suspend fun getByFilename(
        filename: String,
        isTrash: Boolean,
    ): LocalFileStateEntity? = states[filename to isTrash]

    override suspend fun getAll(): List<LocalFileStateEntity> = states.values.toList()

    override suspend fun getAllByTrashStatus(isTrash: Boolean): List<LocalFileStateEntity> =
        states.values.filter { state -> state.isTrash == isTrash }

    override suspend fun upsert(entity: LocalFileStateEntity) {
        states[entity.filename to entity.isTrash] = entity
    }

    override suspend fun upsertAll(entities: List<LocalFileStateEntity>) {
        entities.forEach { entity -> upsert(entity) }
    }

    override suspend fun deleteByFilename(
        filename: String,
        isTrash: Boolean,
    ) {
        states.remove(filename to isTrash)
    }

    override suspend fun clearAll() {
        states.clear()
    }
}
