package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: memo background execution ownership in data repositories.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: memo outbox drains and async memo version recording run only on an injected
 *   application-owned background scope.
 *
 * Scenarios:
 * - Given the injected memo background scope is active, when MemoSynchronizer starts, then pending
 *   outbox work is drained on that scope.
 * - Given the injected memo background scope is cancelled, when a memo save requests another
 *   background drain, then no repository-owned fallback job continues the drain.
 * - Given the injected memo background scope is cancelled, when a memo revision is enqueued, then
 *   no repository-owned fallback job records the revision.
 *
 * Observable outcomes:
 * - Outbox polling count before and after injected-scope cancellation.
 * - Memo version journal append calls after injected-scope cancellation.
 *
 * TDD proof:
 * - Fails before the fix because MemoSynchronizer and AsyncMemoVersionRecorder constructors do not
 *   require injected scopes and instead create their own SupervisorJob-backed CoroutineScope.
 *
 * Excludes:
 * - Room persistence, file rewrite behavior, Hilt component creation, and Android process lifecycle dispatch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoBackgroundOwnershipTest : DataFunSpec() {
    init {
        test("given injected scope cancellation when save requests drain then outbox does not continue on repository scope") {
            runTest {
                val dispatcher = StandardTestDispatcher(testScheduler)
                val backgroundScope = CoroutineScope(SupervisorJob() + dispatcher)
                val refreshEngine = mockk<MemoRefreshEngine>()
                val mutationHandler = mockk<MemoMutationHandler>()
                var outboxPolls = 0
                coEvery { mutationHandler.nextMemoFileOutbox() } coAnswers {
                    outboxPolls += 1
                    null
                }
                coEvery { mutationHandler.saveMemoInDb(any(), any(), any()) } returns
                    SaveDbResult(
                        savePlan = savePlan("memo-save"),
                        outboxId = 10L,
                    )

                val synchronizer =
                    MemoSynchronizer(
                        refreshEngine = refreshEngine,
                        mutationHandler = mutationHandler,
                        outboxScope = backgroundScope,
                        startOutboxCoordinator = true,
                    )

                runCurrent()
                outboxPolls shouldBe 1

                backgroundScope.cancel()
                synchronizer.saveMemo(content = "after cancel", timestamp = 1_700_000_000_000L)
                runCurrent()

                outboxPolls shouldBe 1
            }
        }

        test("given injected scope cancellation when local revision enqueues then recorder does not use fallback scope") {
            runTest {
                val backgroundScope = TestScope(StandardTestDispatcher(testScheduler))
                val memoVersionJournal = mockk<MemoVersionJournal>()
                val recorder =
                    AsyncMemoVersionRecorder(
                        memoVersionJournal = memoVersionJournal,
                        scope = backgroundScope,
                    )
                backgroundScope.cancel()

                recorder.enqueueLocalRevision(
                    memo = memo("memo-history"),
                    lifecycleState = MemoRevisionLifecycleState.TRASHED,
                    origin = MemoRevisionOrigin.LOCAL_TRASH,
                )
                runCurrent()

                coVerify(exactly = 0) {
                    memoVersionJournal.appendLocalRevision(any(), any(), any())
                }
            }
        }
    }
}

private fun savePlan(memoId: String): MemoSavePlan {
    val memo = memo(memoId)
    return MemoSavePlan(
        filename = "2024_01_15.md",
        dateKey = memo.dateKey,
        timestamp = memo.timestamp,
        rawContent = memo.rawContent,
        memo = memo,
    )
}

private fun memo(id: String): Memo =
    Memo(
        id = id,
        timestamp = 1_700_000_000_000L,
        content = "content",
        rawContent = "- 10:00 content",
        dateKey = "2024_01_15",
    )
