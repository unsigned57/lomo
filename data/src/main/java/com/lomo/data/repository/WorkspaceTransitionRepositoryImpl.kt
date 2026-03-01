package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.domain.repository.WorkspaceTransitionRepository
import javax.inject.Inject

class WorkspaceTransitionRepositoryImpl
    @Inject
    constructor(
        private val memoDao: MemoDao,
    ) : WorkspaceTransitionRepository {
        override suspend fun clearMemoStateAfterWorkspaceTransition() {
            memoDao.clearMemoFileOutbox()
            memoDao.clearLocalFileState()
            memoDao.clearTagRefs()
            memoDao.clearAll()
            memoDao.clearTrash()
            memoDao.clearFts()
        }
    }
