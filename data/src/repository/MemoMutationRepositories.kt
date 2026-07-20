package com.lomo.data.repository

import androidx.paging.PagingSource
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.entity.MemoPinEntity
import com.lomo.data.reminder.MemoMutationReminderScheduler
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.repository.MemoTrashRepository

/**
 * Production memo mutation edge. Every write path fails closed unless the Rust engine is Ready
 * and no workspace-switch freeze is active.
 *
 * Domain use cases may also gate; this repository boundary is the last shared choke point so
 * outbox/sync/editor paths cannot bypass the hard gate.
 */
class MemoMutationRepositoryImpl(
    private val memoPinDao: MemoPinDao,
    private val synchronizer: MemoSynchronizer,
    private val reminderScheduler: MemoMutationReminderScheduler,
    private val memoQueryRepository: MemoQueryRepository,
    private val writeAuthority: WorkspaceWriteAuthority,
) : MemoMutationRepository {
    override suspend fun refreshMemos() {
        requireWritableEngine()
        synchronizer.refresh()
    }

    override suspend fun saveMemo(
        content: String,
        timestamp: Long,
        geoLocation: String?,
    ): Memo {
        requireWritableEngine()
        val savedMemo = synchronizer.saveMemo(content, timestamp, geoLocation)
        reminderScheduler.syncForMemo(savedMemo.id)
        return savedMemo
    }

    override suspend fun updateMemo(
        memo: Memo,
        newContent: String,
    ) {
        requireWritableEngine()
        synchronizer.updateMemo(memo, newContent)
        reminderScheduler.syncForMemo(memo.id)
    }

    override suspend fun deleteMemo(memo: Memo) {
        requireWritableEngine()
        synchronizer.deleteMemo(memo)
        reminderScheduler.cancelForMemo(memo.id)
    }

    override suspend fun restoreMemoRevision(
        currentMemo: Memo,
        revisionId: String,
    ) {
        requireWritableEngine()
        synchronizer.restoreMemoRevision(
            currentMemo = currentMemo,
            revisionId = revisionId,
        )
        val restoredMemo = memoQueryRepository.getMemoById(currentMemo.id)
        if (restoredMemo == null) {
            reminderScheduler.cancelForMemo(currentMemo.id)
        } else {
            reminderScheduler.syncForMemo(restoredMemo.id)
        }
    }

    override suspend fun setMemoPinned(
        memoId: String,
        pinned: Boolean,
    ) {
        requireWritableEngine()
        if (pinned) {
            memoPinDao.upsertMemoPin(
                MemoPinEntity(
                    memoId = memoId,
                    pinnedAt = System.currentTimeMillis(),
                ),
            )
        } else {
            memoPinDao.deleteMemoPin(memoId)
        }
    }

    private fun requireWritableEngine() {
        writeAuthority.requireWritable()
    }
}

class MemoTrashRepositoryImpl(
    private val memoTrashDao: MemoTrashDao,
    private val synchronizer: MemoSynchronizer,
    private val writeAuthority: WorkspaceWriteAuthority,
) : MemoTrashRepository {
    override fun getDeletedMemosPagingSource(): PagingSource<Int, Memo> =
        TrashMemoMappingPagingSource(memoTrashDao.getDeletedMemosPagingSource())

    override suspend fun restoreMemo(memo: Memo) {
        requireWritableEngine()
        synchronizer.restoreMemo(memo)
    }

    override suspend fun deletePermanently(memo: Memo) {
        requireWritableEngine()
        synchronizer.deletePermanently(memo)
    }

    override suspend fun clearTrash() {
        requireWritableEngine()
        synchronizer.clearTrash()
    }

    private fun requireWritableEngine() {
        writeAuthority.requireWritable()
    }
}
