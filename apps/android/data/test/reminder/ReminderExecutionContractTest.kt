package com.lomo.data.reminder

// adversarial-audit: the package is claimed CLOSED, but its own spec
// lists residual items these probes pin down:
// (a) the durable ledger never compares identity+triggerAt+mode, so re-applying an identical
//     plan still performs schedule binder calls for unchanged occurrences;
// (b) `setAndAllowWhileIdle` is an inexact AlarmManager API yet is reported as
//     AlarmTriggerMode.ExactAllowWhileIdle, and the schedule receipt has no production consumer;
// (c) LOCKED_BOOT_COMPLETED is still declared although the receiver is not directBootAware and
//     the ledger lives in credential-encrypted storage — an unreachable declaration the spec
//     ordered removed;
// (d) the fire path still renders the full Markdown body for the notification preview instead
//     of consuming the widget plain-text projection `bodyPreview`.

// architectural-boundary-check: manifest declaration and notification-preview source contracts
// are only observable as packaged/source facts (receiver manifest entries, fire-path wiring).
/*
 * Behavior Contract:
 * - Unit under test: reminder scheduling/fire execution edges (ledger dedup, trigger-mode
 *   reporting, manifest reachability, notification preview source).
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: an identical reminder plan is a durable no-op; reported trigger mode matches the
 *   AlarmManager API actually used; unreachable manifest declarations are absent; the fire path
 *   renders the plain-text projection, not the full Markdown body.
 *
 * Scenarios:
 * - Given an identical plan re-applied, when scheduling runs, then no schedule binder calls repeat.
 * - Given setAndAllowWhileIdle is used, when the receipt reports the mode, then it is not
 *   reported as ExactAllowWhileIdle.
 * - Given the receiver manifest, when inspected, then no unreachable LOCKED_BOOT_COMPLETED
 *   declaration remains.
 * - Given an alarm fires, when the notification preview is built, then it consumes the
 *   bodyPreview projection rather than rendering full Markdown.
 *
 * Observable outcomes: binder call counts, reported trigger modes, manifest declarations,
 * notification preview content source.
 *
 * TDD proof:
 * - Each arm fails RED against the pre-fix ledger/API/manifest/preview behavior recorded in the
 *   adversarial note above; GREEN under the repaired execution path.
 *
 * Excludes:
 * - Exact alarm user prompts, Doze policy and widget rendering internals.
 */

import android.app.PendingIntent
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import com.lomo.domain.usecase.SingleDispatcherProvider

private class AuditRecordingPort(
    private val canExact: Boolean,
) : AlarmSchedulePort {
    val schedules = mutableListOf<AlarmScheduleRequest>()
    val cancels = mutableListOf<String>()

    override fun exactAlarmCapability(): ExactAlarmCapability =
        ExactAlarmCapability(canScheduleExactAlarms = canExact, sdkInt = 34)

    override fun schedule(request: AlarmScheduleRequest): AlarmScheduleResult =
        AlarmScheduleResult(mode = AlarmTriggerMode.AlarmClock, triggerAtUtcMillis = request.triggerAtUtcMillis)
            .also { schedules += request }

    override fun cancel(
        occurrenceId: String,
        memoId: String,
        reminderId: String,
    ) {
        cancels += occurrenceId
    }
}

private class AuditGateway(
    private val canExact: Boolean,
) : AlarmPlatformGateway {
    val allowWhileIdleCalls = mutableListOf<Long>()

    override fun canScheduleExactAlarms(): Boolean = canExact

    override fun pendingIntent(
        occurrenceId: String,
        memoId: String,
        reminderId: String,
    ): PendingIntent = mockk(relaxed = true)

    override fun setAlarmClock(
        triggerAtUtcMillis: Long,
        operation: PendingIntent,
    ) = Unit

    override fun setAndAllowWhileIdle(
        triggerAtUtcMillis: Long,
        operation: PendingIntent,
    ) {
        allowWhileIdleCalls += triggerAtUtcMillis
    }

    override fun setInexact(
        triggerAtUtcMillis: Long,
        operation: PendingIntent,
    ) = Unit

    override fun cancel(operation: PendingIntent) = Unit
}

class ReminderExecutionContractTest : DataFunSpec() {
    init {
        test("identical plan re-applied must be a no-op — no repeated schedule binder calls") {
            val dir = createTempDirectory().toFile()
            val port = AuditRecordingPort(canExact = true)
            val scheduler =
                ReminderRollingWindowScheduler(
                    port,
                    ReminderExecutionLedger(dir, SingleDispatcherProvider(Dispatchers.Unconfined)),
                )
            val plan =
                listOf(
                    PlannedReminderAlarm("gen␟r1␟1000", "m1", "r1", 1_000L),
                    PlannedReminderAlarm("gen␟r2␟2000", "m2", "r2", 2_000L),
                )
            scheduler.applyPlan(plan)
            port.schedules.clear()
            port.cancels.clear()

            scheduler.applyPlan(plan)

            withClue("unchanged occurrences must not be rescheduled or cancelled") {
                port.cancels.shouldBeEmpty()
                port.schedules.shouldBeEmpty()
            }
        }

        test("setAndAllowWhileIdle result must not be reported as an exact trigger mode") {
            val gateway = AuditGateway(canExact = false)
            val port = AndroidAlarmSchedulePort(gateway = gateway, sdkInt = 34)
            val result =
                port.schedule(
                    AlarmScheduleRequest(
                        occurrenceId = "gen␟r1␟1000",
                        triggerAtUtcMillis = 1_000L,
                        memoId = "m1",
                        reminderId = "r1",
                    ),
                )
            gateway.allowWhileIdleCalls shouldBe listOf(1_000L)
            withClue("setAndAllowWhileIdle is an inexact doze API and must not wear Exact*") {
                result.mode shouldBe AlarmTriggerMode.AllowWhileIdle
            }
        }

        test("unreachable LOCKED_BOOT_COMPLETED declaration is removed from manifest and action set") {
            val manifest =
                resolveModuleRoot("data")
                    .resolve("src/AndroidManifest.xml")
                    .readText()
            withClue(
                "ReminderBootReceiver is not directBootAware and the reminder ledger lives in " +
                    "credential-encrypted storage, so LOCKED_BOOT_COMPLETED can never legitimately " +
                    "reach it — the declaration must be removed",
            ) {
                manifest.contains("LOCKED_BOOT_COMPLETED") shouldBe false
                ReminderRescheduleBroadcast.ACTIONS.contains(Intent_ACTION_LOCKED_BOOT) shouldBe false
            }
        }

        test("alarm-fired notification preview must not render the full Markdown body") {
            val source =
                resolveModuleRoot("data")
                    .resolve("src/reminder/ReminderAlarmReceiver.kt")
                    .readText()
            withClue(
                "The spec orders the notification title to reuse the plain-text projection " +
                    "preview (StoreMemoSummary.bodyPreview); a full renderMarkdown of the memo " +
                    "body at fire time is the deleted fallback",
            ) {
                source.contains("renderMarkdown") shouldBe false
            }
        }
    }

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDir = File(System.getProperty("user.dir") ?: ".")
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
                currentDir.parentFile?.resolve(moduleName),
            )
        return checkNotNull(
            candidateRoots
                .filterNotNull()
                .firstOrNull { dir -> dir.name == moduleName && dir.resolve("module.yaml").exists() },
        ) { "Failed to resolve $moduleName module root from $currentDir" }
    }

    private companion object {
        const val Intent_ACTION_LOCKED_BOOT = "android.intent.action.LOCKED_BOOT_COMPLETED"
    }
}
