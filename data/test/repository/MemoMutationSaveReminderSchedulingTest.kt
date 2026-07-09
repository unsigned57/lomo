package com.lomo.data.repository

import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.testing.fakes.FakeReminderCoordinator
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoQueryRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MemoMutationRepositoryImpl (save path reminder scheduling)
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: a newly saved memo's reminder marker is scheduled deterministically using the
 *   persisted memo identity returned by the synchronizer, independent of any best-effort query lookup.
 *
 * Scenarios:
 * - Given a saved memo whose content carries a reminder marker and the query lookup cannot yet
 *   observe the freshly persisted memo, when saveMemo runs, then syncForMemo is invoked once with the
 *   saved memo's id and content.
 *
 * Observable outcomes:
 * - FakeReminderCoordinator.syncForMemoCalls contains exactly the saved memo id paired with its content.
 *
 * TDD proof:
 * - Fails before the fix because saveMemo scheduled reminders via a best-effort
 *   withTimeoutOrNull timestamp lookup; when the memo is not yet observable from the query repository
 *   the lookup returns nothing and syncForMemo is never called, so a reminder on a freshly created
 *   memo is silently dropped.
 *
 * Excludes:
 * - AlarmManager scheduling internals, file/db persistence, outbox drain timing.
 */
class MemoMutationSaveReminderSchedulingTest : FunSpec({
    test("given a reminder memo when saved then it is scheduled with the saved memo id even if the query lookup misses") {
        runTest {
            val savedMemo =
                Memo(
                    id = "memo-real-id",
                    timestamp = 1_700_000_000_000L,
                    content = "Pay rent @2026-05-30-09:00",
                    rawContent = "- 09:00 Pay rent @2026-05-30-09:00",
                    dateKey = "2026_05_30",
                )
            val synchronizer = mockk<MemoSynchronizer>()
            coEvery { synchronizer.saveMemo(savedMemo.content, savedMemo.timestamp, null) } returns savedMemo

            val reminderCoordinator = FakeReminderCoordinator()

            // The freshly persisted memo is deliberately not yet observable through the query
            // repository, reproducing the race/timeout window the old lookup-based path depended on.
            val queryRepository = mockk<MemoQueryRepository>()
            every { queryRepository.getAllMemosList() } returns flowOf(emptyList())

            val repository =
                MemoMutationRepositoryImpl(
                    memoPinDao = mockk<MemoPinDao>(),
                    synchronizer = synchronizer,
                    reminderScheduler = reminderCoordinator,
                    memoQueryRepository = queryRepository,
                )

            repository.saveMemo(savedMemo.content, savedMemo.timestamp, null)

            reminderCoordinator.syncForMemoCalls shouldContainExactly listOf(savedMemo.id to savedMemo.content)
        }
    }
})
