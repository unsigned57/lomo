package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoImageDao
import com.lomo.data.local.dao.MemoOutboxDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxIdentityPolicy
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.data.local.entity.MemoImageAttachmentEntity
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.repository.SyncStateResetRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: WorkspaceTransitionRepositoryImpl
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: clear workspace-scoped state after switching workspace roots.
 *
 * Scenarios:
 * - Given a workspace transition with memo-derived tables and sync reset state populated from the
 *   previous workspace, when cleanup runs, then all memo-derived stores are empty and the sync reset
 *   state is cleared.
 * - Given a workspace transition, when memo-derived state is cleared, then sync-state reset runs in
 *   the same transaction after memo cleanup so stale WebDAV/S3/pending conflict state cannot leak
 *   into the rebuilt workspace.
 *
 * Observable outcomes:
 * - Fake DAO state is empty after cleanup and the fake sync resetter exposes no stale state.
 * - Explicit transaction call trace places sync-state reset after memo cleanup and before transaction
 *   end.
 *
 * TDD proof:
 * - Fails before the fix because WorkspaceTransitionRepositoryImpl has no sync-state reset hook.
 *
 * Excludes:
 * - resetter internals, Room SQL details, remote transport state, and UI behavior.
 *
 * Test Change Justification:
 * - Reason category: Meaningful-test migration.
 * - Old behavior/assertion being replaced: relaxed DAO mocks with interaction-only ordering assertions.
 * - Why old assertion is no longer correct: stateful DAO collaborators must be faked and observed
 *   through cleared state instead of relaxed interaction checks.
 * - Coverage preserved by: fake DAO state assertions plus a separate explicit ordering contract.
 * - Why this is not fitting the test to the implementation: expectations describe the public cleanup
 *   effect of a workspace transition and its documented transaction ordering.
 */
class WorkspaceTransitionRepositoryImplTest : DataFunSpec() {
    init {
        test(
            "given stale workspace stores when transition cleanup runs " +
                "then memo state and sync reset state are cleared",
        ) {
            runTest {
                val memoDao = FakeWorkspaceTransitionMemoDao()
                val localFileStateDao = FakeLocalFileStateDao()
                val syncStateResetRepository = FakeSyncStateResetRepository()
                memoDao.seedStaleMemoState()
                localFileStateDao.upsert(staleLocalFileState())
                val repository =
                    WorkspaceTransitionRepositoryImpl(
                        memoWriteDao = memoDao,
                        memoOutboxDao = memoDao,
                        memoTagDao = memoDao,
                        memoImageDao = memoDao,
                        memoTrashDao = memoDao,
                        localFileStateDao = localFileStateDao,
                        syncStateResetRepository = syncStateResetRepository,
                        runInTransaction = { block -> block() },
                    )

                repository.clearMemoStateAfterWorkspaceTransition()

                memoDao.memoRows().shouldBeEmpty()
                memoDao.outboxRows().shouldBeEmpty()
                memoDao.tagRows().shouldBeEmpty()
                memoDao.imageRows().shouldBeEmpty()
                memoDao.trashRows().shouldBeEmpty()
                localFileStateDao.getAll().shouldBeEmpty()
                syncStateResetRepository.workspaceScopedStatePresent shouldBe false
                syncStateResetRepository.resetCount shouldBe 1
            }
        }

        test(
            "given workspace transition when cleanup runs " +
                "then reset is transactional and ordered after memo cleanup",
        ) {
            runTest {
                val callTrace = mutableListOf<String>()
                val memoDao = FakeWorkspaceTransitionMemoDao(callTrace = callTrace)
                val localFileStateDao = FakeLocalFileStateDao(callTrace = callTrace)
                val syncStateResetRepository = FakeSyncStateResetRepository(callTrace = callTrace)
                val repository =
                    WorkspaceTransitionRepositoryImpl(
                        memoWriteDao = memoDao,
                        memoOutboxDao = memoDao,
                        memoTagDao = memoDao,
                        memoImageDao = memoDao,
                        memoTrashDao = memoDao,
                        localFileStateDao = localFileStateDao,
                        syncStateResetRepository = syncStateResetRepository,
                        runInTransaction = { block ->
                            callTrace += "tx-start"
                            block()
                            callTrace += "tx-end"
                        },
                    )

                repository.clearMemoStateAfterWorkspaceTransition()

                callTrace shouldBe
                    listOf(
                        "tx-start",
                        "outbox",
                        "local-state",
                        "tags",
                        "images",
                        "memos",
                        "trash",
                        "sync-state",
                        "tx-end",
                    )
            }
        }
    }
}

private class FakeWorkspaceTransitionMemoDao(
    private val callTrace: MutableList<String>? = null,
) : MemoWriteDao,
    MemoOutboxDao,
    MemoTagDao,
    MemoImageDao,
    MemoTrashDao {
    private val memos = linkedMapOf<String, MemoEntity>()
    private val outbox = linkedMapOf<Long, MemoFileOutboxEntity>()
    private val tagRefs = linkedSetOf<MemoTagCrossRefEntity>()
    private val imageRefs = linkedSetOf<MemoImageAttachmentEntity>()
    private val trash = linkedMapOf<String, TrashMemoEntity>()
    private var nextOutboxId = 1L

    fun seedStaleMemoState() {
        val memo = staleMemo()
        memos[memo.id] = memo
        outbox[1L] = staleOutbox()
        tagRefs += MemoTagCrossRefEntity(memoId = memo.id, tag = "old")
        imageRefs += MemoImageAttachmentEntity(memoId = memo.id, imagePath = "media/old.jpg")
        trash[memo.id] = staleTrashMemo()
        nextOutboxId = 2L
    }

    fun memoRows(): List<MemoEntity> = memos.values.toList()

    fun outboxRows(): List<MemoFileOutboxEntity> = outbox.values.toList()

    fun tagRows(): Set<MemoTagCrossRefEntity> = tagRefs.toSet()

    fun imageRows(): Set<MemoImageAttachmentEntity> = imageRefs.toSet()

    fun trashRows(): List<TrashMemoEntity> = trash.values.toList()

    override suspend fun insertMemos(memos: List<MemoEntity>) {
        memos.forEach { memo -> this.memos[memo.id] = memo }
    }

    override suspend fun insertMemo(memo: MemoEntity) {
        memos[memo.id] = memo
    }

    override suspend fun deleteMemo(memo: MemoEntity) {
        memos.remove(memo.id)
    }

    override suspend fun deleteMemoById(id: String) {
        memos.remove(id)
    }

    override suspend fun deleteMemosByIds(ids: List<String>) {
        ids.forEach(memos::remove)
    }

    override suspend fun clearAll() {
        callTrace?.add("memos")
        memos.clear()
    }

    override suspend fun deleteMemosNotIn(ids: List<String>) {
        memos.keys.retainAll(ids.toSet())
    }

    override suspend fun deleteMemosByDate(date: String) {
        memos.values.removeAll { memo -> memo.date == date }
    }

    override suspend fun insertMemoFileOutboxIgnoringDuplicate(item: MemoFileOutboxEntity): Long {
        if (outbox.values.any { existing -> existing.idempotencyKey == item.idempotencyKey }) {
            return -1L
        }
        val id = item.id.takeIf { persistedId -> persistedId != 0L } ?: nextOutboxId++
        outbox[id] = item.copy(id = id)
        return id
    }

    override suspend fun getMemoFileOutboxIdByIdempotencyKey(idempotencyKey: String): Long? =
        outbox.values.firstOrNull { item -> item.idempotencyKey == idempotencyKey }?.id

    override suspend fun getMemoFileOutboxBatch(limit: Int): List<MemoFileOutboxEntity> =
        outbox.values.sortedBy(MemoFileOutboxEntity::id).take(limit)

    override suspend fun claimNextMemoFileOutboxRow(
        claimToken: String,
        claimedAt: Long,
        staleBefore: Long,
    ): Int {
        val claimable =
            outbox.values
                .sortedBy(MemoFileOutboxEntity::id)
                .firstOrNull { item -> item.claimToken == null || item.claimUpdatedAt.orMax() <= staleBefore }
                ?: return 0
        outbox[claimable.id] =
            claimable.copy(
                claimToken = claimToken,
                claimUpdatedAt = claimedAt,
                updatedAt = claimedAt,
            )
        return 1
    }

    override suspend fun getMemoFileOutboxByClaimToken(claimToken: String): MemoFileOutboxEntity? =
        outbox.values.firstOrNull { item -> item.claimToken == claimToken }

    override suspend fun deleteMemoFileOutboxById(id: Long) {
        outbox.remove(id)
    }

    override suspend fun clearMemoFileOutbox() {
        callTrace?.add("outbox")
        outbox.clear()
    }

    override suspend fun markMemoFileOutboxFailed(
        id: Long,
        updatedAt: Long,
        lastError: String?,
    ) {
        val item = outbox[id] ?: return
        outbox[id] =
            item.copy(
                retryCount = item.retryCount + 1,
                updatedAt = updatedAt,
                lastError = lastError,
                claimToken = null,
                claimUpdatedAt = null,
            )
    }

    override suspend fun getMemoFileOutboxCount(): Int = outbox.size

    override suspend fun insertTagRefs(refs: List<MemoTagCrossRefEntity>) {
        tagRefs += refs
    }

    override suspend fun deleteTagRefsByMemoId(memoId: String) {
        tagRefs.removeAll { ref -> ref.memoId == memoId }
    }

    override suspend fun deleteTagRefsByMemoIds(memoIds: List<String>) {
        tagRefs.removeAll { ref -> ref.memoId in memoIds }
    }

    override suspend fun clearTagRefs() {
        callTrace?.add("tags")
        tagRefs.clear()
    }

    override suspend fun insertImageRefs(refs: List<MemoImageAttachmentEntity>) {
        imageRefs += refs
    }

    override suspend fun deleteImageRefsByMemoId(memoId: String) {
        imageRefs.removeAll { ref -> ref.memoId == memoId }
    }

    override suspend fun deleteImageRefsByMemoIds(memoIds: List<String>) {
        imageRefs.removeAll { ref -> ref.memoId in memoIds }
    }

    override suspend fun clearImageRefs() {
        callTrace?.add("images")
        imageRefs.clear()
    }

    override fun getDeletedMemosPage(
        limit: Int,
        offset: Int,
    ): Flow<List<TrashMemoEntity>> =
        flowOf(
            if (limit <= 0 || offset < 0) {
                emptyList()
            } else {
                sortedTrashRows().drop(offset).take(limit)
            },
        )

    override fun getDeletedMemosPagingSource(): androidx.paging.PagingSource<Int, TrashMemoEntity> =
        object : androidx.paging.PagingSource<Int, TrashMemoEntity>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TrashMemoEntity> {
                val offset = params.key ?: 0
                val rows = sortedTrashRows().drop(offset).take(params.loadSize)
                return LoadResult.Page(
                    data = rows,
                    prevKey = null,
                    nextKey = if (rows.size < params.loadSize) null else offset + rows.size,
                )
            }

            override fun getRefreshKey(state: androidx.paging.PagingState<Int, TrashMemoEntity>): Int? =
                state.anchorPosition
        }

    override suspend fun getDeletedMemos(): List<TrashMemoEntity> = sortedTrashRows()

    override suspend fun insertTrashMemos(memos: List<TrashMemoEntity>) {
        memos.forEach { memo -> trash[memo.id] = memo }
    }

    override suspend fun insertTrashMemo(memo: TrashMemoEntity) {
        trash[memo.id] = memo
    }

    override suspend fun getTrashMemo(id: String): TrashMemoEntity? = trash[id]

    override suspend fun getTrashMemosByDate(date: String): List<TrashMemoEntity> =
        trash.values.filter { memo -> memo.date == date }

    override suspend fun deleteTrashMemoById(id: String) {
        trash.remove(id)
    }

    override suspend fun deleteTrashMemosByIds(ids: List<String>) {
        ids.forEach(trash::remove)
    }

    override suspend fun deleteTrashMemosByDate(date: String) {
        trash.values.removeAll { memo -> memo.date == date }
    }

    override suspend fun clearTrash() {
        callTrace?.add("trash")
        trash.clear()
    }

    private fun sortedTrashRows(): List<TrashMemoEntity> =
        trash.values.sortedWith(
            compareByDescending<TrashMemoEntity> { memo -> memo.timestamp }
                .thenByDescending { memo -> memo.id },
        )
}

private class FakeLocalFileStateDao(
    private val callTrace: MutableList<String>? = null,
) : LocalFileStateDao {
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
        callTrace?.add("local-state")
        states.clear()
    }
}

private class FakeSyncStateResetRepository(
    private val callTrace: MutableList<String>? = null,
) : SyncStateResetRepository {
    var workspaceScopedStatePresent: Boolean = true
        private set
    var resetCount: Int = 0
        private set

    override suspend fun resetWorkspaceScopedSyncState() {
        callTrace?.add("sync-state")
        workspaceScopedStatePresent = false
        resetCount += 1
    }
}

private fun staleMemo(): MemoEntity =
    MemoEntity(
        id = "old-memo",
        timestamp = 10L,
        updatedAt = 20L,
        content = "old memo",
        searchContent = "old memo",
        rawContent = "old memo",
        date = "2026-05-01",
        tags = "old",
        imageUrls = "media/old.jpg",
    )

private fun staleTrashMemo(): TrashMemoEntity =
    TrashMemoEntity(
        id = "old-trash",
        timestamp = 30L,
        updatedAt = 40L,
        content = "old trash",
        rawContent = "old trash",
        date = "2026-05-01",
        tags = "old",
        imageUrls = "media/trash.jpg",
    )

private fun staleOutbox(): MemoFileOutboxEntity {
    val identity =
        MemoFileOutboxIdentityPolicy.forUpdate(
            memoId = "old-memo",
            memoDate = "2026-05-01",
            memoRawContent = "old memo",
            newContent = "old memo changed",
        )
    return MemoFileOutboxEntity(
        id = 1L,
        operation = MemoFileOutboxOp.UPDATE,
        operationId = identity.operationId,
        idempotencyKey = identity.idempotencyKey,
        memoId = "old-memo",
        memoDate = "2026-05-01",
        memoTimestamp = 10L,
        memoRawContent = "old memo",
        newContent = "old memo changed",
        createRawContent = null,
        createdAt = 50L,
        updatedAt = 60L,
    )
}

private fun staleLocalFileState(): LocalFileStateEntity =
    LocalFileStateEntity(
        filename = "old.md",
        isTrash = false,
        lastKnownModifiedTime = 70L,
        lastSeenAt = 80L,
    )

private fun Long?.orMax(): Long = this ?: Long.MAX_VALUE
