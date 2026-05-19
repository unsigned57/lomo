package com.lomo.data.repository

import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.entity.MemoPinEntity
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.ReminderCoordinator
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.repository.MemoTrashRepository
import dagger.Lazy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val REMINDER_SAVE_LOOKUP_TIMEOUT_MS = 500L

@Singleton
class MemoMutationRepositoryImpl
    @Inject
    constructor(
        private val memoPinDao: MemoPinDao,
        private val synchronizer: MemoSynchronizer,
        private val reminderCoordinator: Lazy<ReminderCoordinator>,
        private val memoQueryRepository: Lazy<MemoQueryRepository>,
    ) : MemoMutationRepository {
        override suspend fun refreshMemos() {
            synchronizer.refresh()
        }

        override suspend fun saveMemo(
            content: String,
            timestamp: Long,
            geoLocation: String?,
        ) {
            synchronizer.saveMemo(content, timestamp, geoLocation)
            // Skip when content has no reminder marker shape — most memos take this path.
            if (!content.contains('@') || !content.contains(':')) return
            runCatching {
                val savedMemo =
                    withTimeoutOrNull(REMINDER_SAVE_LOOKUP_TIMEOUT_MS) {
                        memoQueryRepository
                            .get()
                            .getAllMemosList()
                            .first()
                            .firstOrNull { it.timestamp == timestamp }
                    }
                savedMemo?.let { reminderCoordinator.get().syncForMemo(it.id, it.content) }
            }
        }

        override suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            synchronizer.updateMemo(memo, newContent)
            reminderCoordinator.get().syncForMemo(memo.id, newContent)
        }

        override suspend fun deleteMemo(memo: Memo) {
            synchronizer.deleteMemo(memo)
            reminderCoordinator.get().cancelForMemo(memo.id)
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
