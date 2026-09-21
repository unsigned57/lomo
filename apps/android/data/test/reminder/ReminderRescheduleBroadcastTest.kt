package com.lomo.data.reminder

/*
 * Behavior Contract:
 * Capability: boot/upgrade/timezone broadcasts only enqueue a durable rebuild demand and never
 * call rebuildAll inside the receiver window; owning layer: data; priority: P0.
 * Scenarios:
 * - Given BOOT_COMPLETED, when enqueueReminderRescheduleDemand runs, then demand.enqueue runs.
 * - Given an unrelated action, when applied, then demand is not enqueued.
 * - Given TIMEZONE_CHANGED / TIME_SET / DATE_CHANGED / package replaced, then accepted.
 * Observable outcomes: enqueue count and accept boolean.
 * TDD proof: ./kotlin test --include-module=data --include-classes='com.lomo.data.reminder.ReminderRescheduleBroadcastTest'
 * Excludes: WorkManager execution, reminder identity (T30), notification delivery (C10 remainder).
 */

import android.content.Intent
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

class ReminderRescheduleBroadcastTest : DataFunSpec() {
    init {
        test("given boot completed when demand is dispatched then rebuild is not invoked") {
            val demand = RecordingReminderRebuildDemand()

            val accepted =
                enqueueReminderRescheduleDemand(
                    action = Intent.ACTION_BOOT_COMPLETED,
                    demand = demand,
                )

            accepted shouldBe true
            demand.enqueueCount shouldBe 1
        }

        test("given timezone and time changes when dispatched then durable demand is enqueued") {
            val demand = RecordingReminderRebuildDemand()

            enqueueReminderRescheduleDemand(Intent.ACTION_TIMEZONE_CHANGED, demand) shouldBe true
            enqueueReminderRescheduleDemand(Intent.ACTION_TIME_CHANGED, demand) shouldBe true
            enqueueReminderRescheduleDemand(Intent.ACTION_DATE_CHANGED, demand) shouldBe true
            enqueueReminderRescheduleDemand(Intent.ACTION_MY_PACKAGE_REPLACED, demand) shouldBe true

            demand.enqueueCount shouldBe 4
        }

        test("given unrelated action when dispatched then demand is not enqueued") {
            val demand = RecordingReminderRebuildDemand()

            enqueueReminderRescheduleDemand(Intent.ACTION_SCREEN_ON, demand) shouldBe false
            enqueueReminderRescheduleDemand(null, demand) shouldBe false
            demand.enqueueCount shouldBe 0
        }
    }
}

private class RecordingReminderRebuildDemand : ReminderRebuildDemand {
    var enqueueCount: Int = 0

    override fun enqueue() {
        enqueueCount += 1
    }
}
