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
        val allMemos: () -> Flow<List<Memo>> = { memoRepository.getAllMemosList() }

        val deletedMemos: () -> Flow<List<Memo>> = { memoRepository.getDeletedMemosList() }

        val isSyncing: () -> Flow<Boolean> = { memoRepository.isSyncing() }

        val searchMemos: (String) -> Flow<List<Memo>> = { query -> memoRepository.searchMemosList(query) }

        val memosByTag: (String) -> Flow<List<Memo>> = { tag -> memoRepository.getMemosByTagList(tag) }

        val activeDayCount: () -> Flow<Int> = { memoRepository.getActiveDayCount() }

        val memoCount: () -> Flow<Int> = { memoRepository.getMemoCountFlow() }

        val memoCountByDate: () -> Flow<Map<String, Int>> = { memoRepository.getMemoCountByDateFlow() }

        val tagCounts: () -> Flow<List<MemoTagCount>> = { memoRepository.getTagCountsFlow() }

        val getMemoById: suspend (String) -> Memo? = { memoId -> memoRepository.getMemoById(memoId) }

        val setMemoPinned: suspend (String, Boolean) -> Unit =
            { memoId, pinned -> memoRepository.setMemoPinned(memoId, pinned) }

        val restoreMemo: suspend (Memo) -> Unit = { memo -> memoRepository.restoreMemo(memo) }

        val deletePermanently: suspend (Memo) -> Unit = { memo -> memoRepository.deletePermanently(memo) }

        val clearTrash: suspend () -> Unit = { memoRepository.clearTrash() }
    }
