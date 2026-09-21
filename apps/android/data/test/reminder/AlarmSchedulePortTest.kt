package com.lomo.data.reminder

/*
 * Behavior Contract:
 * - Unit under test: ReminderRollingWindowScheduler + ReminderExecutionLedger +
 *   AlarmSchedulePort (fake)
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: pure schedule/cancel port applies a rolling-window plan keyed on durable
 *   occurrence ids, records scheduled occurrences in a durable ledger, and cancels stale
 *   PendingIntents across process restarts — no in-process map is the cancellation authority.
 *
 * Scenarios:
 * - Given a plan with two alarms, when applied, then the port schedules both and records modes.
 * - Given a plan update that drops one occurrence, when applied, then the dropped occurrence
 *   PendingIntent is cancelled by occurrence id.
 * - Given occurrences scheduled by a previous scheduler instance, when a new instance applies a
 *   plan, then stale occurrences are cancelled from the durable ledger (process-death parity).
 * - Given a memo delete after restart, when cancelForMemo runs, then the reminder's recorded
 *   occurrences are cancelled from the ledger.
 * - Given one reminder with two occurrences (catch-up + future), when applied, then both are
 *   scheduled under distinct occurrence identities.
 * - Given the fake reports canScheduleExact=false, when capability is read, then false is observed.
 * - Given schedule returns a platform error string, when applied, then the result surfaces it.
 *
 * Observable outcomes: schedule/cancel call lists keyed on occurrence id, AlarmScheduleResult
 * mode/error, ledger persistence across instances, capability.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.reminder.AlarmSchedulePortTest'
 * - RED: class/types missing before P3-08; durable-ledger identity added under C08/T30.
 *
 * Excludes:
 * - Real AlarmManager delivery, notification UI, Rust plan generation (P3-07 host tests).
 * Test Change Justification:
 * - Reason category: domain contract change (occurrence identity plus durable execution ledger).
 * - Old behavior/assertion being replaced: plan identity and cancellation keyed on reminder id
 *   via an in-process PendingIntent map.
 * - Why old assertion is no longer correct: occurrences carry durable ids recorded in a ledger
 *   that survives process death; cancellation must be ledger-driven.
 * - Coverage preserved by: schedule/cancel scenarios rewritten around occurrence ids, plus new
 *   restart-parity and multi-occurrence scenarios.
 * - Why this is not fitting the test to the implementation: ledger persistence and occurrence
 *   keying are the product contract.
 */

import com.lomo.domain.usecase.FakeDispatcherProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers

private class FakeAlarmSchedulePort(
    private var canExact: Boolean = true,
    private var scheduleMode: AlarmTriggerMode = AlarmTriggerMode.AlarmClock,
    private var platformError: String? = null,
) : AlarmSchedulePort {
    val schedules = mutableListOf<AlarmScheduleRequest>()
    val cancels = mutableListOf<Triple<String, String, String>>()

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
        occurrenceId: String,
        memoId: String,
        reminderId: String,
    ) {
        cancels += Triple(occurrenceId, memoId, reminderId)
    }

    fun setCanExact(value: Boolean) {
        canExact = value
    }
}

private fun ledgerAt(dir: File): ReminderExecutionLedger =
    ReminderExecutionLedger(dir, FakeDispatcherProvider(Dispatchers.Unconfined))

private fun alarm(
    occurrenceId: String,
    memoId: String,
    reminderId: String,
    triggerAtUtcMillis: Long,
    isCatchUp: Boolean = false,
) = PlannedReminderAlarm(occurrenceId, memoId, reminderId, triggerAtUtcMillis, isCatchUp)

class AlarmSchedulePortTest : FunSpec({
    test("given a rolling window plan when applied then schedule is invoked per alarm with modes") {
        val dir = createTempDirectory().toFile()
        val port = FakeAlarmSchedulePort(scheduleMode = AlarmTriggerMode.AlarmClock)
        val scheduler = ReminderRollingWindowScheduler(port, ledgerAt(dir))

        val result =
            scheduler.applyPlan(
                listOf(
                    alarm("occ-1", "m1", "r1", 1_000L),
                    alarm("occ-2", "m2", "r2", 2_000L, isCatchUp = true),
                ),
            )

        result.scheduled shouldHaveSize 2
        result.scheduled.map { it.mode }.toSet() shouldBe setOf(AlarmTriggerMode.AlarmClock)
        port.schedules.map { it.occurrenceId } shouldBe listOf("occ-1", "occ-2")
        port.schedules[0].triggerAtUtcMillis shouldBe 1_000L
    }

    test("given plan drops an occurrence when reapplied then cancel is reported for the stale occurrence") {
        val dir = createTempDirectory().toFile()
        val port = FakeAlarmSchedulePort()
        val scheduler = ReminderRollingWindowScheduler(port, ledgerAt(dir))

        scheduler.applyPlan(listOf(alarm("occ-1", "m1", "r1", 1_000L)))
        port.cancels.clear()
        val result = scheduler.applyPlan(listOf(alarm("occ-2", "m2", "r2", 2_000L)))

        result.cancelledCount shouldBe 1
        port.cancels.map { it.first } shouldBe listOf("occ-1")
        port.schedules.last().occurrenceId shouldBe "occ-2"
    }

    test("given a new scheduler instance on the same ledger when a plan applies then stale occurrences cancel durably") {
        val dir = createTempDirectory().toFile()
        val port = FakeAlarmSchedulePort()
        // First "process": schedules occurrences, then dies.
        ReminderRollingWindowScheduler(port, ledgerAt(dir))
            .applyPlan(listOf(alarm("occ-old", "m1", "r1", 1_000L)))
        port.cancels.clear()

        // Second "process": fresh scheduler, same durable ledger directory.
        val restarted = ReminderRollingWindowScheduler(port, ledgerAt(dir))
        restarted.applyPlan(listOf(alarm("occ-new", "m1", "r1", 2_000L)))

        port.cancels.map { it.first } shouldBe listOf("occ-old")
        port.schedules.last().occurrenceId shouldBe "occ-new"
    }

    test("given a memo delete after restart when cancelForMemo runs then recorded occurrences are cancelled") {
        val dir = createTempDirectory().toFile()
        val port = FakeAlarmSchedulePort()
        ReminderRollingWindowScheduler(port, ledgerAt(dir))
            .applyPlan(
                listOf(
                    alarm("occ-a", "memo-1", "reminder-a", 1_000L),
                    alarm("occ-b", "memo-1", "reminder-b", 2_000L),
                    alarm("occ-other", "memo-2", "reminder-c", 3_000L),
                ),
            )
        port.cancels.clear()

        // Fresh scheduler instance: the durable ledger, not a process-local map, is the authority.
        ReminderRollingWindowScheduler(port, ledgerAt(dir))
            .cancelForMemo("memo-1", setOf("reminder-a", "reminder-b"))

        port.cancels.map { it.first }.toSet() shouldBe setOf("occ-a", "occ-b")
        port.cancels.any { it.first == "occ-other" } shouldBe false
    }

    test("given one reminder with catch-up and future occurrences when applied then both schedule under distinct identities") {
        val dir = createTempDirectory().toFile()
        val port = FakeAlarmSchedulePort()
        val scheduler = ReminderRollingWindowScheduler(port, ledgerAt(dir))

        scheduler.applyPlan(
            listOf(
                alarm("gen␟r1␟1000", "m1", "r1", 1_000L, isCatchUp = true),
                alarm("gen␟r1␟2000", "m1", "r1", 2_000L),
            ),
        )

        port.schedules.map { it.occurrenceId }.toSet() shouldBe setOf("gen␟r1␟1000", "gen␟r1␟2000")
    }

    test("given exact alarm capability when queried then port value is returned") {
        val dir = createTempDirectory().toFile()
        val port = FakeAlarmSchedulePort(canExact = false)
        val scheduler = ReminderRollingWindowScheduler(port, ledgerAt(dir))
        scheduler.capability().canScheduleExactAlarms shouldBe false
        port.setCanExact(true)
        scheduler.capability().canScheduleExactAlarms shouldBe true
    }

    test("given platform error on schedule when applied then result carries the diagnostic") {
        val dir = createTempDirectory().toFile()
        val port =
            FakeAlarmSchedulePort(
                scheduleMode = AlarmTriggerMode.ExactAllowWhileIdle,
                platformError = "exact alarm denied",
            )
        val scheduler = ReminderRollingWindowScheduler(port, ledgerAt(dir))
        val result = scheduler.applyPlan(listOf(alarm("occ-e", "m", "r", 9L)))
        result.scheduled.single().mode shouldBe AlarmTriggerMode.ExactAllowWhileIdle
        result.scheduled.single().platformError shouldBe "exact alarm denied"
        result.scheduled.single().platformError shouldNotBe null
    }

    test("given a corrupt ledger file when a scheduler opens it then it is quarantined and rebuilt") {
        val dir = createTempDirectory().toFile()
        File(dir, "execution_ledger.v1.json").writeText("{not json")
        val port = FakeAlarmSchedulePort()
        val scheduler = ReminderRollingWindowScheduler(port, ledgerAt(dir))

        scheduler.applyPlan(listOf(alarm("occ-fresh", "m1", "r1", 1_000L)))

        port.schedules.map { it.occurrenceId } shouldBe listOf("occ-fresh")
        dir.listFiles().orEmpty().any { it.name.contains(".corrupt-") } shouldBe true
    }
})
