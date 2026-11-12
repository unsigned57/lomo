package com.lomo.data.reminder

/*
 * Behavior Contract:
 * Capability: full reminder restoration re-registers OS alarms even when the durable ledger
 * survives their removal; owning layer: data; priority: P0.
 * Scenarios:
 * - Given a retained ledger after the OS clears alarms, when a new scheduler rebuilds, then
 *   the unchanged future occurrence is registered again.
 * - Given an existing alarm and an unavailable plan, when rebuild fails, then the existing
 *   registration and cancellation authority are preserved.
 * Observable outcomes: platform registrations and persisted ledger entries across instances.
 * TDD proof: RED/GREEN command and logs under target/lomo/reports/manual/reminder-alarm/.
 * Excludes: Rust recurrence planning, physical sound and Android notification settings.
 */

import android.content.Context
import com.lomo.data.engine.store.StorePlannedAlarm
import com.lomo.data.engine.store.StorePort
import com.lomo.data.engine.store.StoreReminderPlan
import com.lomo.data.local.datastore.LomoReminderPreferencesStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.ReminderInterval
import com.lomo.domain.usecase.SingleDispatcherProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory

private class FakeRestorableAlarmPlatform : AlarmSchedulePort {
    val registrations = mutableMapOf<String, AlarmScheduleRequest>()

    override fun exactAlarmCapability() = ExactAlarmCapability(true, 35)

    override fun schedule(request: AlarmScheduleRequest): AlarmScheduleResult {
        registrations[request.occurrenceId] = request
        return AlarmScheduleResult(AlarmTriggerMode.AlarmClock, request.triggerAtUtcMillis)
    }

    override fun cancel(occurrenceId: String, memoId: String, reminderId: String) {
        registrations.remove(occurrenceId)
    }
}

class ReminderAlarmRestoreTest : DataFunSpec() {
    init {
        test(
            "given alarms removed by the OS when rebuilding from a retained ledger then the alarm is registered again",
        ) {
            runTest {
                val directory = createTempDirectory("lomo-alarm-restore").toFile()
                try {
                    val platform = FakeRestorableAlarmPlatform()
                    val plan = futurePlan()
                    val store = mockk<StorePort> { every { queryReminderPlan(any()) } returns plan }
                    val firstLedger = ledger(directory, StandardTestDispatcher(testScheduler))
                    scheduler(store, platform, firstLedger, backgroundScope).rebuildAll()
                    platform.registrations.keys shouldContainExactly listOf("occurrence")

                    // Device restart/force-stop removes AlarmManager registrations, not app files.
                    platform.registrations.clear()
                    val restoredLedger = ledger(directory, StandardTestDispatcher(testScheduler))
                    scheduler(store, platform, restoredLedger, backgroundScope).rebuildAll()

                    platform.registrations.keys shouldContainExactly listOf("occurrence")
                    restoredLedger.snapshot().keys shouldContainExactly listOf("occurrence")
                } finally {
                    directory.deleteRecursively()
                }
            }
        }

        test("given plan query failure when rebuilding then existing alarms and ledger remain intact") {
            runTest {
                val directory = createTempDirectory("lomo-alarm-restore").toFile()
                try {
                    val platform = FakeRestorableAlarmPlatform()
                    val store = mockk<StorePort> { every { queryReminderPlan(any()) } returns futurePlan() }
                    val ledger = ledger(directory, StandardTestDispatcher(testScheduler))
                    val scheduler = scheduler(store, platform, ledger, backgroundScope)
                    scheduler.rebuildAll()
                    val before = ledger.snapshot()
                    every { store.queryReminderPlan(any()) } throws IllegalStateException("plan unavailable")

                    shouldThrow<IllegalStateException> { scheduler.rebuildAll() }.message shouldBe "plan unavailable"

                    platform.registrations.keys shouldContainExactly listOf("occurrence")
                    ledger.snapshot() shouldBe before
                } finally {
                    directory.deleteRecursively()
                }
            }
        }
    }

    private fun ledger(directory: File, dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        ReminderExecutionLedger(directory, SingleDispatcherProvider(dispatcher))

    private fun scheduler(
        store: StorePort,
        platform: FakeRestorableAlarmPlatform,
        ledger: ReminderExecutionLedger,
        scope: CoroutineScope,
    ) = AlarmManagerReminderScheduler(
        context = mockk<Context>(),
        storePort = store,
        readiness = FakeEngineReadinessRepository(EngineReadiness.Ready),
        reminderPreferences = mockk<LomoReminderPreferencesStore> {
            every { reminderInterval } returns flowOf(ReminderInterval.DEFAULT)
        },
        appScope = scope,
        schedulePort = platform,
        rollingWindow = ReminderRollingWindowScheduler(platform, ledger),
    )

    private fun futurePlan() = StoreReminderPlan(
        alarms = listOf(StorePlannedAlarm("occurrence", "reminder", "memo", 4_000_000_000_000L, false)),
        droppedCount = 0,
        workspaceGeneration = "workspace",
    )
}
