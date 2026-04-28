package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoImageDao
import com.lomo.data.local.dao.MemoOutboxDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.domain.repository.WorkspaceTransitionRepository

class WorkspaceTransitionRepositoryImpl
    constructor(
        private val memoWriteDao: MemoWriteDao,
        private val memoOutboxDao: MemoOutboxDao,
        private val memoTagDao: MemoTagDao,
        private val memoImageDao: MemoImageDao,
        private val memoTrashDao: MemoTrashDao,
        private val localFileStateDao: LocalFileStateDao,
        private val runInTransaction: suspend (suspend () -> Unit) -> Unit,
    ) : WorkspaceTransitionRepository {
        override suspend fun clearMemoStateAfterWorkspaceTransition() {
            runInTransaction {
                memoOutboxDao.clearMemoFileOutbox()
                localFileStateDao.clearAll()
                memoTagDao.clearTagRefs()
                memoImageDao.clearImageRefs()
                memoWriteDao.clearAll()
                memoTrashDao.clearTrash()
            }
        }
    }
