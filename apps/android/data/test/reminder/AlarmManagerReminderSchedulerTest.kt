package com.lomo.data.reminder

import android.content.Context
import com.lomo.data.engine.store.StorePort
import com.lomo.data.engine.store.StoreReminderPlan
import com.lomo.data.local.datastore.LomoReminderPreferencesStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.model.ReminderInterval
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.EngineReadinessRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Test Change Justification:
 * Reason category: explicit activation input adaptation.
 * Old behavior/assertion being replaced: the root-only fixture omitted attachment binding input.
 * Why old assertion is no longer correct: root-only activation now explicitly selects committed attachment bindings.
 * Coverage preserved by: every reminder plan and alarm assertion is unchanged.
 * Why this is not fitting the test to the implementation: no reminder behavior is changed by the fixture signature.
 * TDD proof: Regression preservation - verification-green.log under target/lomo/reports/manual/directory-selection/.
 * - Unit under test: AlarmManagerReminderScheduler preference source.
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: the snooze interval lives in the main DataStore — reads flow from the injected
 *   preference store, validated writes persist through it, and snooze consumes the stored value.
 *
 * Scenarios:
 * - Given a store-backed interval, when the scheduler exposes it, then the StateFlow reflects
 *   the stored value.
 * - Given a vocabulary option write, when setGlobalInterval runs, then the store persists
 *   it; values outside the option set are unrepresentable by the enum signature.
 * - Given a stored interval, when a reminder is snoozed, then the durable binding uses that
 *   exact duration.
 *
 * Observable outcomes:
 * - Store writes, StateFlow value, and the snoozeReminder duration argument.
 *
 * TDD proof:
 * - RED: the scheduler previously owned a private SharedPreferences island; the injected-store
 *   constructor did not exist.
 *
 * Excludes:
 * - Plan generation, rolling-window application, and platform alarm delivery.
 * Test Change Justification:
 * Reason category: test fixture ownership and assertion strengthening.
 * Old behavior/assertion being replaced: Relaxed mocks and an unowned coroutine scope are replaced; a duration assertion is added.
 * Why old assertion is no longer correct: existing outcome assertions remain valid and retained.
 * Coverage preserved by: Stored interval flow, durable writes and exact snooze command duration remain asserted.
 * Why this is not fitting the test to the implementation: no expected product outcome is weakened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmManagerReminderSchedulerTest : DataFunSpec() {
    private class FakeReminderPreferencesStore(initial: ReminderInterval = ReminderInterval.DEFAULT) :
        LomoReminderPreferencesStore {
        val intervalFlow = MutableStateFlow(initial)
        val writes = mutableListOf<ReminderInterval>()

        override val reminderInterval: Flow<ReminderInterval>
            get() = intervalFlow

        override suspend fun updateReminderInterval(interval: ReminderInterval) {
            writes += interval
            intervalFlow.value = interval
        }
    }

    private fun scheduler(
        preferences: FakeReminderPreferencesStore,
        scope: CoroutineScope,
        storePort: StorePort = mockk(),
        readiness: EngineReadinessRepository = FakeEngineReadinessRepository(),
        rollingWindow: ReminderRollingWindowScheduler = mockk(),
    ): AlarmManagerReminderScheduler = AlarmManagerReminderScheduler(
        context = mockk<Context>(),
        storePort = storePort,
        readiness = readiness,
        reminderPreferences = preferences,
        appScope = scope,
        schedulePort = mockk(),
        rollingWindow = rollingWindow,
    )

    init {
        test("given store-backed interval when observed then the state flow reflects the stored value") {
            runTest {
                val preferences = FakeReminderPreferencesStore(initial = ReminderInterval.THIRTY_MINUTES)
                val scheduler = scheduler(preferences, backgroundScope)
                runCurrent()

                scheduler.globalInterval.value shouldBe ReminderInterval.THIRTY_MINUTES
            }
        }

        test("given supported interval when written then the store persists it and flow updates") {
            runTest {
                val preferences = FakeReminderPreferencesStore()
                val scheduler = scheduler(preferences, backgroundScope)

                scheduler.setGlobalInterval(ReminderInterval.SIXTY_MINUTES)

                preferences.writes shouldContainExactly listOf(ReminderInterval.SIXTY_MINUTES)
                preferences.intervalFlow.value shouldBe ReminderInterval.SIXTY_MINUTES
            }
        }

        test("given stored interval when a reminder is snoozed then the durable binding uses that duration") {
            runTest {
                val preferences = FakeReminderPreferencesStore(initial = ReminderInterval.FIFTEEN_MINUTES)
                val durationSlot = slot<ReminderInterval>()
                val storePort =
                    mockk<StorePort> {
                        every { snoozeReminder(any(), capture(durationSlot)) } returns Unit
                        every { queryReminderPlan(any()) } returns
                            StoreReminderPlan(alarms = emptyList(), droppedCount = 0, workspaceGeneration = "g1")
                    }
                val readiness =
                    FakeEngineReadinessRepository().apply {
                        activateWorkspace(StorageLocation("ws"), mediaChange = null)
                    }
                val rollingWindow =
                    mockk<ReminderRollingWindowScheduler> {
                        coEvery { applyPlanForMemos(any(), any()) } returns
                            RollingWindowApplyResult(scheduled = emptyList(), cancelledCount = 0, unchangedCount = 0)
                    }
                val scheduler =
                    scheduler(
                        preferences,
                        scope = backgroundScope,
                        storePort = storePort,
                        readiness = readiness,
                        rollingWindow = rollingWindow,
                    )

                scheduler.snooze(memoId = "memo-1", reminderId = "rem-1")

                durationSlot.captured shouldBe ReminderInterval.FIFTEEN_MINUTES
                verify(exactly = 1) { storePort.snoozeReminder("rem-1", ReminderInterval.FIFTEEN_MINUTES) }
            }
        }
    }
}
