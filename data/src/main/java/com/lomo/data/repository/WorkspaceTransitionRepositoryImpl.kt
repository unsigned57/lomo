package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoFtsDao
import com.lomo.data.local.dao.MemoOutboxDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.domain.repository.WorkspaceTransitionRepository
import javax.inject.Inject

class WorkspaceTransitionRepositoryImpl
    @Inject
    constructor(
        private val memoWriteDao: MemoWriteDao,
        private val memoOutboxDao: MemoOutboxDao,
        private val memoTagDao: MemoTagDao,
        private val memoFtsDao: MemoFtsDao,
        private val memoTrashDao: MemoTrashDao,
        private val localFileStateDao: LocalFileStateDao,
    ) : WorkspaceTransitionRepository {
        override suspend fun clearMemoStateAfterWorkspaceTransition() {
            memoOutboxDao.clearMemoFileOutbox()
            localFileStateDao.clearAll()
            memoTagDao.clearTagRefs()
            memoWriteDao.clearAll()
            memoTrashDao.clearTrash()
            memoFtsDao.clearFts()
        }
    }
