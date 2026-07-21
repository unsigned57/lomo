package com.lomo.data.reminder

/*
 * Behavior Contract:
 * - Unit under test: ReminderRollingWindowScheduler + AlarmSchedulePort (fake)
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: pure schedule/cancel port applies a rolling-window plan, reports exact-alarm
 *   capability and actual trigger mode, without owning recurrence/fired/done/snooze semantics.
 *
 * Scenarios:
 * - Given a plan with two alarms, when applied, then the port schedules both and records modes.
 * - Given a plan update that drops one identity, when applied, then the dropped identity is cancelled.
 * - Given the fake reports canScheduleExact=false, when capability is read, then false is observed.
 * - Given schedule returns a platform error string, when applied, then the result surfaces it.
 *
 * Observable outcomes: schedule/cancel call lists, AlarmScheduleResult mode/error, capability.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.reminder.AlarmSchedulePortTest'
 * - RED: class/types missing before P3-08.
 *
 * Excludes:
 * - Real AlarmManager delivery, notification UI, Rust plan generation (P3-07 host tests).
 */

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private class FakeAlarmSchedulePort(
    private var canExact: Boolean = true,
    private var scheduleMode: AlarmTriggerMode = AlarmTriggerMode.AlarmClock,
    private var platformError: String? = null,
) : AlarmSchedulePort {
    val schedules = mutableListOf<AlarmScheduleRequest>()
    val cancels = mutableListOf<Triple<Int, String, String>>()

    override fun exactAlarmCapability(): ExactAlarmCapability =
        ExactAlarmCapability(canScheduleExactAlarms = canExact, sdkInt = 34)

    override fun schedule(request: AlarmScheduleRequest): AlarmScheduleResult {
        schedules += request
        return AlarmScheduleResult(
            mode = scheduleMode,
            triggerAtUtcMillis = request.triggerAtUtcMillis,
            platformError = platformError,
        )
    }

    override fun cancel(
        requestCode: Int,
        memoId: String,
        reminderId: String,
    ) {
        cancels += Triple(requestCode, memoId, reminderId)
    }

    fun setCanExact(value: Boolean) {
        canExact = value
    }

    fun setMode(mode: AlarmTriggerMode) {
        scheduleMode = mode
    }

    fun setError(message: String?) {
        platformError = message
    }
}

class AlarmSchedulePortTest : FunSpec({
    test("given a rolling window plan when applied then schedule is invoked per alarm with modes") {
        val port = FakeAlarmSchedulePort(scheduleMode = AlarmTriggerMode.AlarmClock)
        val scheduler = ReminderRollingWindowScheduler(port) { memo, rem -> (memo + rem).hashCode() }

        val result =
            scheduler.applyPlan(
                listOf(
                    PlannedReminderAlarm("m1", "r1", 1_000L),
                    PlannedReminderAlarm("m2", "r2", 2_000L, isCatchUp = true),
                ),
            )

        result.scheduled shouldHaveSize 2
        result.scheduled.map { it.mode }.toSet() shouldBe setOf(AlarmTriggerMode.AlarmClock)
        port.schedules.map { it.memoId to it.reminderId } shouldBe listOf("m1" to "r1", "m2" to "r2")
        port.schedules[0].triggerAtUtcMillis shouldBe 1_000L
    }

    test("given plan drops an identity when reapplied then cancel is reported for the stale alarm") {
        val port = FakeAlarmSchedulePort()
        val scheduler = ReminderRollingWindowScheduler(port) { memo, rem -> (memo + rem).hashCode() }

        scheduler.applyPlan(listOf(PlannedReminderAlarm("m1", "r1", 1_000L)))
        port.cancels.clear()
        val result = scheduler.applyPlan(listOf(PlannedReminderAlarm("m2", "r2", 2_000L)))

        result.cancelledCount shouldBe 1
        port.cancels.any { it.second == "m1" && it.third == "r1" } shouldBe true
        port.schedules.last().memoId shouldBe "m2"
    }

    test("given exact alarm capability when queried then port value is returned") {
        val port = FakeAlarmSchedulePort(canExact = false)
        val scheduler = ReminderRollingWindowScheduler(port)
        scheduler.capability().canScheduleExactAlarms shouldBe false
        port.setCanExact(true)
        scheduler.capability().canScheduleExactAlarms shouldBe true
    }

    test("given platform error on schedule when applied then result carries the diagnostic") {
        val port =
            FakeAlarmSchedulePort(
                scheduleMode = AlarmTriggerMode.ExactAllowWhileIdle,
                platformError = "exact alarm denied",
            )
        val scheduler = ReminderRollingWindowScheduler(port) { _, _ -> 1 }
        val result = scheduler.applyPlan(listOf(PlannedReminderAlarm("m", "r", 9L)))
        result.scheduled.single().mode shouldBe AlarmTriggerMode.ExactAllowWhileIdle
        result.scheduled.single().platformError shouldBe "exact alarm denied"
        result.scheduled.single().platformError shouldNotBe null
    }
})
