package com.lomo.data.reminder

/*
 * Behavior Contract:
 * - Unit under test: AndroidAlarmSchedulePort (production AlarmSchedulePort).
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: choose AlarmClock vs ExactAllowWhileIdle vs InexactFallback from capability + SDK
 *   and SecurityException fallbacks; cancel uses same pending-intent identity.
 *
 * Scenarios:
 * - Given SDK ≥ S and canScheduleExact=true, when schedule runs, then AlarmClock mode is used.
 * - Given SDK ≥ S and canScheduleExact=false, when schedule runs, then ExactAllowWhileIdle is used.
 * - Given setAlarmClock throws SecurityException then allow-while-idle succeeds, when schedule runs,
 *   then ExactAllowWhileIdle is reported with platformError.
 * - Given both exact paths throw SecurityException, when schedule runs, then InexactFallback is used.
 * - Given cancel, when invoked, then gateway cancel receives the pending intent for that identity.
 *
 * Observable outcomes: AlarmScheduleResult.mode/platformError; gateway call lists.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.reminder.AndroidAlarmSchedulePortTest'
 * - RED: AndroidAlarmSchedulePort was zero-hit under host coverage (C1).
 *
 * Excludes:
 * - Real AlarmManager delivery, notification UI, ReminderAlarmReceiver lifecycle.
 */

import android.app.PendingIntent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk

private class RecordingAlarmGateway(
    var canExact: Boolean = true,
    var alarmClockThrows: SecurityException? = null,
    var allowWhileIdleThrows: SecurityException? = null,
) : AlarmPlatformGateway {
    val alarmClockCalls = mutableListOf<Long>()
    val allowWhileIdleCalls = mutableListOf<Long>()
    val inexactCalls = mutableListOf<Long>()
    val cancelCalls = mutableListOf<PendingIntent>()
    val pendingIntents = mutableMapOf<Triple<String, String, Int>, PendingIntent>()

    override fun canScheduleExactAlarms(): Boolean = canExact

    override fun pendingIntent(
        memoId: String,
        reminderId: String,
        requestCode: Int,
    ): PendingIntent =
        pendingIntents.getOrPut(Triple(memoId, reminderId, requestCode)) {
            mockk(relaxed = true)
        }

    override fun setAlarmClock(
        triggerAtUtcMillis: Long,
        operation: PendingIntent,
    ) {
        alarmClockThrows?.let { throw it }
        alarmClockCalls += triggerAtUtcMillis
    }

    override fun setAndAllowWhileIdle(
        triggerAtUtcMillis: Long,
        operation: PendingIntent,
    ) {
        allowWhileIdleThrows?.let { throw it }
        allowWhileIdleCalls += triggerAtUtcMillis
    }

    override fun setInexact(
        triggerAtUtcMillis: Long,
        operation: PendingIntent,
    ) {
        inexactCalls += triggerAtUtcMillis
    }

    override fun cancel(operation: PendingIntent) {
        cancelCalls += operation
    }
}

class AndroidAlarmSchedulePortTest : FunSpec({
    val request =
        AlarmScheduleRequest(
            requestCode = 42,
            triggerAtUtcMillis = 1_700_000_000_000L,
            memoId = "memo-1",
            reminderId = "rem-1",
        )

    test("sdk S+ with exact permission uses AlarmClock") {
        val gateway = RecordingAlarmGateway(canExact = true)
        val port = AndroidAlarmSchedulePort(gateway = gateway, sdkInt = 34)
        val result = port.schedule(request)
        result.mode shouldBe AlarmTriggerMode.AlarmClock
        result.triggerAtUtcMillis shouldBe request.triggerAtUtcMillis
        gateway.alarmClockCalls shouldBe listOf(request.triggerAtUtcMillis)
        gateway.allowWhileIdleCalls shouldBe emptyList()
        port.exactAlarmCapability().canScheduleExactAlarms shouldBe true
        port.exactAlarmCapability().sdkInt shouldBe 34
    }

    test("sdk S+ without exact permission uses ExactAllowWhileIdle") {
        val gateway = RecordingAlarmGateway(canExact = false)
        val port = AndroidAlarmSchedulePort(gateway = gateway, sdkInt = 34)
        val result = port.schedule(request)
        result.mode shouldBe AlarmTriggerMode.ExactAllowWhileIdle
        gateway.allowWhileIdleCalls shouldBe listOf(request.triggerAtUtcMillis)
        gateway.alarmClockCalls shouldBe emptyList()
    }

    test("SecurityException on AlarmClock falls back to allow-while-idle") {
        val gateway =
            RecordingAlarmGateway(
                canExact = true,
                alarmClockThrows = SecurityException("exact denied"),
            )
        val port = AndroidAlarmSchedulePort(gateway = gateway, sdkInt = 34)
        val result = port.schedule(request)
        result.mode shouldBe AlarmTriggerMode.ExactAllowWhileIdle
        result.platformError shouldBe "exact denied"
        gateway.allowWhileIdleCalls shouldBe listOf(request.triggerAtUtcMillis)
    }

    test("double SecurityException falls back to inexact set") {
        val gateway =
            RecordingAlarmGateway(
                canExact = true,
                alarmClockThrows = SecurityException("exact denied"),
                allowWhileIdleThrows = SecurityException("idle denied"),
            )
        val port = AndroidAlarmSchedulePort(gateway = gateway, sdkInt = 34)
        val result = port.schedule(request)
        result.mode shouldBe AlarmTriggerMode.InexactFallback
        result.platformError shouldBe "idle denied"
        gateway.inexactCalls shouldBe listOf(request.triggerAtUtcMillis)
    }

    test("cancel uses pending intent for the same identity") {
        val gateway = RecordingAlarmGateway()
        val port = AndroidAlarmSchedulePort(gateway = gateway, sdkInt = 34)
        port.cancel(requestCode = 42, memoId = "memo-1", reminderId = "rem-1")
        gateway.cancelCalls.shouldHaveSize(1)
        val expected = gateway.pendingIntent("memo-1", "rem-1", 42)
        gateway.cancelCalls.single() shouldBe expected
    }
})
