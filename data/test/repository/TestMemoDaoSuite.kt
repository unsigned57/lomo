/*
 * Behavior Contract:
 * - Unit under test: TestMemoDaoSuite (test helper, not production code).
 * - Behavior focus: aggregates all memo DAO interfaces for mutation test fixtures.
 * - Observable outcomes: memoMutationDaoBundle() produces a valid MemoMutationDaoBundle.
 * - TDD proof: Compilation failure on Kotest transition - test infrastructure, no production behavior change.
 * - Excludes: database queries, Room implementation.
 */
package com.lomo.data.repository

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.data.local.dao.DefaultMainListDao
import com.lomo.data.local.dao.MemoBrowseDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoFtsDao
import com.lomo.data.local.dao.MemoImageDao
import com.lomo.data.local.dao.MemoIdentityDao
import com.lomo.data.local.dao.MemoOutboxDao
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.data.local.dao.MemoStatisticsDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao

internal interface TestMemoDaoSuite :
    DefaultMainListDao,
    MemoBrowseDao,
    MemoDao,
    MemoWriteDao,
    MemoTagDao,
    MemoImageDao,
    MemoFtsDao,
    MemoIdentityDao,
    MemoTrashDao,
    MemoOutboxDao,
    MemoSearchDao,
    MemoStatisticsDao,
    MemoPinDao

internal fun testMemoMutationDaoBundle(
    dao: TestMemoDaoSuite,
    runInTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
): MemoMutationDaoBundle =
    MemoMutationDaoBundle(
        memoDao = dao,
        memoWriteDao = dao,
        memoTagDao = dao,
        memoImageDao = dao,
        memoIdentityDao = dao,
        memoTrashDao = dao,
        memoOutboxDao = dao,
        runInTransaction = runInTransaction,
    )
