package com.lomo.app.feature.common

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.MemoRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class MemoUiCoordinator
    @Inject
    constructor(
        private val memoRepository: MemoRepository,
    ) {
        fun allMemos(): Flow<List<Memo>> = memoRepository.getAllMemosList()

        fun deletedMemos(): Flow<List<Memo>> = memoRepository.getDeletedMemosList()

        fun isSyncing(): Flow<Boolean> = memoRepository.isSyncing()

        fun searchMemos(query: String): Flow<List<Memo>> = memoRepository.searchMemosList(query)

        fun memosByTag(tag: String): Flow<List<Memo>> = memoRepository.getMemosByTagList(tag)

        fun activeDayCount(): Flow<Int> = memoRepository.getActiveDayCount()

        fun memoCount(): Flow<Int> = memoRepository.getMemoCountFlow()

        fun memoCountByDate(): Flow<Map<String, Int>> = memoRepository.getMemoCountByDateFlow()

        fun tagCounts(): Flow<List<MemoTagCount>> = memoRepository.getTagCountsFlow()

        suspend fun getMemoById(memoId: String): Memo? = memoRepository.getMemoById(memoId)

        suspend fun setMemoPinned(
            memoId: String,
            pinned: Boolean,
        ) {
            memoRepository.setMemoPinned(memoId, pinned)
        }

        suspend fun restoreMemo(memo: Memo) {
            memoRepository.restoreMemo(memo)
        }

        suspend fun deletePermanently(memo: Memo) {
            memoRepository.deletePermanently(memo)
        }
    }
