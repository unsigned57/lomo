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

class MemoMutationRepositoryImpl(
    private val memoPinDao: MemoPinDao,
    private val synchronizer: MemoSynchronizer,
    private val reminderScheduler: MemoMutationReminderScheduler,
    private val memoQueryRepository: MemoQueryRepository,
) : MemoMutationRepository {
        override suspend fun refreshMemos() {
            synchronizer.refresh()
        }

        override suspend fun saveMemo(
            content: String,
            timestamp: Long,
            geoLocation: String?,
        ): Memo {
            val savedMemo = synchronizer.saveMemo(content, timestamp, geoLocation)
            reminderScheduler.syncForMemo(savedMemo.id, savedMemo.content)
            return savedMemo
        }

        override suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            synchronizer.updateMemo(memo, newContent)
            reminderScheduler.syncForMemo(memo.id, newContent)
        }

        override suspend fun deleteMemo(memo: Memo) {
            synchronizer.deleteMemo(memo)
            reminderScheduler.cancelForMemo(memo.id)
        }

        override suspend fun restoreMemoRevision(
            currentMemo: Memo,
            revisionId: String,
        ) {
            synchronizer.restoreMemoRevision(
                currentMemo = currentMemo,
                revisionId = revisionId,
            )
            val restoredMemo = memoQueryRepository.getMemoById(currentMemo.id)
            if (restoredMemo == null) {
                reminderScheduler.cancelForMemo(currentMemo.id)
            } else {
                reminderScheduler.syncForMemo(restoredMemo.id, restoredMemo.content)
            }
        }

        override suspend fun setMemoPinned(
            memoId: String,
            pinned: Boolean,
        ) {
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
    }

class MemoTrashRepositoryImpl(
    private val memoTrashDao: MemoTrashDao,
    private val synchronizer: MemoSynchronizer,
) : MemoTrashRepository {
        override fun getDeletedMemosPagingSource(): PagingSource<Int, Memo> =
            TrashMemoMappingPagingSource(memoTrashDao.getDeletedMemosPagingSource())

        override suspend fun restoreMemo(memo: Memo) {
            synchronizer.restoreMemo(memo)
        }

        override suspend fun deletePermanently(memo: Memo) {
            synchronizer.deletePermanently(memo)
        }

        override suspend fun clearTrash() {
            synchronizer.clearTrash()
        }
    }
