package com.lomo.data.repository

import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.entity.MemoPinEntity
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoTrashRepository
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoMutationRepositoryImpl
    @Inject
    constructor(
        private val memoPinDao: MemoPinDao,
        private val synchronizer: MemoSynchronizer,
    ) : MemoMutationRepository {
        override suspend fun refreshMemos() {
            synchronizer.refresh()
        }

        override suspend fun saveMemo(
            content: String,
            timestamp: Long,
        ) {
            synchronizer.saveMemo(content, timestamp)
        }

        override suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            synchronizer.updateMemo(memo, newContent)
        }

        override suspend fun deleteMemo(memo: Memo) {
            synchronizer.deleteMemo(memo)
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

@Singleton
class MemoTrashRepositoryImpl
    @Inject
    constructor(
        private val memoTrashDao: MemoTrashDao,
        private val synchronizer: MemoSynchronizer,
    ) : MemoTrashRepository {
        override fun getDeletedMemosList() =
            memoTrashDao.getDeletedMemosFlow().map { entities -> entities.map { it.toDomain() } }

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
