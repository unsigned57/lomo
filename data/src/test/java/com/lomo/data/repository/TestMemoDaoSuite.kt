package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoFtsDao
import com.lomo.data.local.dao.MemoIdentityDao
import com.lomo.data.local.dao.MemoOutboxDao
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao

internal interface TestMemoDaoSuite :
    MemoDao,
    MemoWriteDao,
    MemoTagDao,
    MemoFtsDao,
    MemoIdentityDao,
    MemoTrashDao,
    MemoOutboxDao,
    MemoSearchDao,
    MemoPinDao

internal fun testMemoMutationDaoBundle(
    dao: TestMemoDaoSuite,
    runInTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
): MemoMutationDaoBundle =
    MemoMutationDaoBundle(
        memoDao = dao,
        memoWriteDao = dao,
        memoTagDao = dao,
        memoFtsDao = dao,
        memoIdentityDao = dao,
        memoTrashDao = dao,
        memoOutboxDao = dao,
        runInTransaction = runInTransaction,
    )
