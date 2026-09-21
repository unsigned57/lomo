package com.lomo.data.reminder

/*
 * Behavior Contract:
 * Capability: reminder rebuild WorkManager work requests an engine session and does not crash
 * when the workspace is unmounted; owning layer: data; priority: P0.
 * Scenarios:
 * - Given Ready workspace, when doWork runs, then rebuildAll runs once and Result.success.
 * - Given no workspace authority, when doWork runs, then rebuildAll is not called and Result.success
 *   (deferred, not process crash, not infinite retry).
 * Observable outcomes: rebuildAll count and ListenableWorker.Result type.
 * TDD proof: ./kotlin test --include-module=data --include-classes='com.lomo.data.reminder.ReminderRebuildWorkerTest'
 * Excludes: AlarmManager binder, reminder identity, notification delivery.
 */

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.data.testing.fakes.FakeReminderCoordinator
import com.lomo.domain.model.EngineReadiness
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

class ReminderRebuildWorkerTest : DataFunSpec() {
    init {
        test("given ready workspace when worker runs then rebuildAll executes") {
            runTest {
                val coordinator = FakeReminderCoordinator()
                val readiness = FakeEngineReadinessRepository(EngineReadiness.Ready)
                val worker =
                    ReminderRebuildWorker(
                        appContext = mockk<Context>(relaxed = true),
                        workerParams = mockk<WorkerParameters>(relaxed = true),
                        reminderCoordinator = coordinator,
                        engineReadiness = readiness,
                    )

                val result = worker.doWork()

                result.shouldBeInstanceOf<ListenableWorker.Result.Success>()
                coordinator.rebuildAllCount shouldBe 1
            }
        }

        test("given unmounted workspace when worker runs then rebuildAll is not called") {
            runTest {
                val coordinator = FakeReminderCoordinator()
                val readiness = FakeEngineReadinessRepository(EngineReadiness.AwaitingWorkspaceSelection)
                readiness.clearWorkspace()
                val worker =
                    ReminderRebuildWorker(
                        appContext = mockk<Context>(relaxed = true),
                        workerParams = mockk<WorkerParameters>(relaxed = true),
                        reminderCoordinator = coordinator,
                        engineReadiness = readiness,
                    )

                val result = worker.doWork()

                result.shouldBeInstanceOf<ListenableWorker.Result.Success>()
                coordinator.rebuildAllCount shouldBe 0
            }
        }
    }
}
