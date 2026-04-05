package com.lomo.data.repository

import com.lomo.domain.model.Memo
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
class MemoSynchronizerUpdateSchedulingTest {
    @MockK(relaxed = true)
    private lateinit var refreshEngine: MemoRefreshEngine

    @MockK(relaxed = true)
    private lateinit var mutationHandler: MemoMutationHandler

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
    }

    @Test
    fun `updateMemo returns after db-first update is enqueued without waiting for synchronous file rewrite`() {
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
