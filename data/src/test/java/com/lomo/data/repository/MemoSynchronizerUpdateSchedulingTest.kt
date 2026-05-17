package com.lomo.data.repository


import com.lomo.domain.model.Memo
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.lomo.data.testing.DataFunSpec

/*
 * Test Contract:
 * - Unit under test: MemoSynchronizer
 * - Behavior focus: existing memo edits must return after the DB-first mutation path is queued, without waiting
 *   for the synchronous file rewrite path.
 * - Observable outcomes: updateMemo returns while the legacy synchronous update path is still blocked, delegates
 *   to updateMemoInDb exactly once, and never invokes the blocking updateMemo handler.
 * - Red phase: Fails before the fix because MemoSynchronizer.updateMemo still calls MemoMutationHandler.updateMemo,
 *   which blocks on synchronous file rewrite before the UI-visible edit can complete.
 * - Excludes: outbox drain completion timing, widget refresh, and Room/file persistence internals.
 */
class MemoSynchronizerUpdateSchedulingTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("updateMemo returns after db-first update is enqueued without waiting for synchronous file rewrite") { `updateMemo returns after db-first update is enqueued without waiting for synchronous file rewrite`() }

        test("deleteMemo returns after db-first trash enqueue without waiting for synchronous file rewrite") { `deleteMemo returns after db-first trash enqueue without waiting for synchronous file rewrite`() }

        test("restoreMemo returns after db-first restore enqueue without waiting for synchronous file rewrite") { `restoreMemo returns after db-first restore enqueue without waiting for synchronous file rewrite`() }
    }


    @MockK(relaxed = true)
    private lateinit var refreshEngine: MemoRefreshEngine

    @MockK(relaxed = true)
    private lateinit var mutationHandler: MemoMutationHandler

    private fun setUp() {
        MockKAnnotations.init(this)
    }

    private fun `updateMemo returns after db-first update is enqueued without waiting for synchronous file rewrite`() {
        runBlocking {
            val memo = testMemo()
            val blockingUpdateGate = CompletableDeferred<Unit>()
            coEvery { mutationHandler.updateMemo(memo, "after") } coAnswers {
                blockingUpdateGate.await()
            }
            coEvery { mutationHandler.updateMemoInDb(memo, "after") } returns 41L

            val synchronizer =
                MemoSynchronizer(
                    refreshEngine = refreshEngine,
                    mutationHandler = mutationHandler,
                    startOutboxCoordinator = false,
                )
            val executor = Executors.newSingleThreadExecutor()

            try {
                val future = executor.submit<Unit> { runBlocking { synchronizer.updateMemo(memo, "after") } }
                future.get(200, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }

            coVerify(exactly = 1) { mutationHandler.updateMemoInDb(memo, "after") }
            coVerify(exactly = 0) { mutationHandler.updateMemo(memo, "after") }
            blockingUpdateGate.complete(Unit)
        }
    }

    private fun `deleteMemo returns after db-first trash enqueue without waiting for synchronous file rewrite`() {
        runBlocking {
            val memo = testMemo()
            val blockingDeleteGate = CompletableDeferred<Unit>()
            coEvery { mutationHandler.deleteMemo(memo) } coAnswers {
                blockingDeleteGate.await()
            }
            coEvery { mutationHandler.deleteMemoInDb(memo) } returns 52L

            val synchronizer =
                MemoSynchronizer(
                    refreshEngine = refreshEngine,
                    mutationHandler = mutationHandler,
                    startOutboxCoordinator = false,
                )
            val executor = Executors.newSingleThreadExecutor()

            try {
                val future = executor.submit<Unit> { runBlocking { synchronizer.deleteMemo(memo) } }
                future.get(200, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }

            coVerify(exactly = 1) { mutationHandler.deleteMemoInDb(memo) }
            coVerify(exactly = 0) { mutationHandler.deleteMemo(memo) }
            blockingDeleteGate.complete(Unit)
        }
    }

    private fun `restoreMemo returns after db-first restore enqueue without waiting for synchronous file rewrite`() {
        runBlocking {
            val memo = testMemo().copy(isDeleted = true)
            val blockingRestoreGate = CompletableDeferred<Unit>()
            coEvery { mutationHandler.restoreMemo(memo) } coAnswers {
                blockingRestoreGate.await()
            }
            coEvery { mutationHandler.restoreMemoInDb(memo) } returns 63L

            val synchronizer =
                MemoSynchronizer(
                    refreshEngine = refreshEngine,
                    mutationHandler = mutationHandler,
                    startOutboxCoordinator = false,
                )
            val executor = Executors.newSingleThreadExecutor()

            try {
                val future = executor.submit<Unit> { runBlocking { synchronizer.restoreMemo(memo) } }
                future.get(200, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }

            coVerify(exactly = 1) { mutationHandler.restoreMemoInDb(memo) }
            coVerify(exactly = 0) { mutationHandler.restoreMemo(memo) }
            blockingRestoreGate.complete(Unit)
        }
    }

    private fun testMemo(): Memo =
        Memo(
            id = "memo-update",
            timestamp = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            content = "before",
            rawContent = "- 10:00 before",
            dateKey = "2024_01_15",
        )
}
