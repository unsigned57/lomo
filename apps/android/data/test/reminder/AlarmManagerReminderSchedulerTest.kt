package com.lomo.data.reminder

import android.content.Context
import com.lomo.data.engine.store.StorePort
import com.lomo.data.engine.store.StoreReminderPlan
import com.lomo.data.local.datastore.LomoReminderPreferencesStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.ReminderInterval
import com.lomo.domain.model.WorkspaceAuthority
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmManagerReminderSchedulerTest : DataFunSpec() {
    private class FakeReminderPreferencesStore(
        initial: ReminderInterval = ReminderInterval.DEFAULT,
    ) : LomoReminderPreferencesStore {
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
        storePort: StorePort = mockk(relaxed = true),
        readiness: EngineReadinessRepository = mockk(relaxed = true),
        rollingWindow: ReminderRollingWindowScheduler = mockk(relaxed = true),
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
    ): AlarmManagerReminderScheduler =
        AlarmManagerReminderScheduler(
            context = mockk<Context>(relaxed = true),
            storePort = storePort,
            readiness = readiness,
            reminderPreferences = preferences,
            appScope = scope,
            schedulePort = mockk(relaxed = true),
            rollingWindow = rollingWindow,
        )

    init {
        test("given store-backed interval when observed then the state flow reflects the stored value") {
            runTest {
                val preferences = FakeReminderPreferencesStore(initial = ReminderInterval.THIRTY_MINUTES)
                val scheduler = scheduler(preferences)

                scheduler.globalInterval.value shouldBe ReminderInterval.THIRTY_MINUTES
            }
        }

        test("given supported interval when written then the store persists it and flow updates") {
            runTest {
                val preferences = FakeReminderPreferencesStore()
                val scheduler = scheduler(preferences)

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
                    mockk<StorePort>(relaxed = true) {
                        every { snoozeReminder(any(), capture(durationSlot)) } returns Unit
                        every { queryReminderPlan(any()) } returns
                            StoreReminderPlan(alarms = emptyList(), droppedCount = 0, workspaceGeneration = "g1")
                    }
                val readiness =
                    mockk<EngineReadinessRepository> {
                        every { workspaceAuthority } returns
                            MutableStateFlow(WorkspaceAuthority("ws", 1L, 0u))
                    }
                val rollingWindow =
                    mockk<ReminderRollingWindowScheduler> {
                        coEvery { applyPlanForMemos(any(), any()) } returns
                            RollingWindowApplyResult(scheduled = emptyList(), cancelledCount = 0, unchangedCount = 0)
                    }
                val scheduler =
                    scheduler(
                        preferences,
                        storePort = storePort,
                        readiness = readiness,
                        rollingWindow = rollingWindow,
                    )

                scheduler.snooze(memoId = "memo-1", reminderId = "rem-1")

                verify(exactly = 1) { storePort.snoozeReminder("rem-1", ReminderInterval.FIFTEEN_MINUTES) }
            }
        }
    }
}
