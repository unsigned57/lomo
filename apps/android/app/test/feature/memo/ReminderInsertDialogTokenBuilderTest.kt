package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Recurrence
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.repository.ReminderTokenFactory
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalDateTime

/*
 * Behavior Contract:
 * - Unit under test: buildReminderToken
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: convert reminder dialog date, time, repeat, interval, and recurrence selections into the
 *   owner-issued reminder token inserted into memo content (via ReminderTokenFactory).
 *
 * Scenarios:
 * - Given repeat count, custom interval, and daily recurrence selections, when building the token, then the
 *   factory is asked with the dialog-derived LocalDateTime and fields.
 * - Given a one-shot weekly recurrence selection, when building the token, then the factory receives
 *   weekly recurrence without inventing a second Kotlin grammar.
 *
 * Observable outcomes:
 * - returned token string from the owner factory.
 *
 * TDD proof:
 * - Red phase fails before the dialog routes construction through ReminderTokenFactory.
 *
 * Excludes:
 * - DatePicker rendering, TimePicker rendering, localized labels, and domain parser internals.
 *
 * Test Change Justification:
 * - Reason category: reminder token construction ownership moved to ReminderTokenFactory.
 * - Old behavior/assertion being replaced: dialog-local token string assembly / ad-hoc grammar.
 * - Why old assertion is no longer correct: production inserts only owner-issued tokens so app and
 *   data share one grammar with workspace construction contracts.
 * - Coverage preserved by: dialog field mapping into factory inputs and returned token string
 *   remain asserted for one-shot and recurring selections.
 * - Why this is not fitting the test to the implementation: outcomes stay the inserted token
 *   string, not AlarmManager delivery.
 */
class ReminderInsertDialogTokenBuilderTest : AppFunSpec() {
    init {
        test("given repeat interval and daily recurrence when building token then owner factory is used") {
            val factory =
                RecordingReminderTokenFactory(
                    result = "@2026-05-22-17:51x5i15rd",
                )
            val token =
                buildReminderToken(
                    date = LocalDate.of(2026, 5, 22),
                    hour = 17,
                    minute = 51,
                    repeatCount = 5,
                    intervalMinutes = 15,
                    recurrence = Recurrence.DAILY,
                    tokenFactory = factory,
                )

            token shouldBe "@2026-05-22-17:51x5i15rd"
            factory.lastDueAt shouldBe LocalDateTime.of(2026, 5, 22, 17, 51)
            factory.lastRepeatCount shouldBe 5
            factory.lastIntervalMinutes shouldBe 15
            factory.lastRecurrence shouldBe Recurrence.DAILY
        }

        test("given one-shot weekly recurrence when building token then factory receives weekly fields") {
            val factory =
                RecordingReminderTokenFactory(
                    result = "@2026-05-22-17:51rw",
                )
            val token =
                buildReminderToken(
                    date = LocalDate.of(2026, 5, 22),
                    hour = 17,
                    minute = 51,
                    repeatCount = 1,
                    intervalMinutes = 30,
                    recurrence = Recurrence.WEEKLY,
                    tokenFactory = factory,
                )

            token shouldBe "@2026-05-22-17:51rw"
            factory.lastRecurrence shouldBe Recurrence.WEEKLY
            factory.lastRepeatCount shouldBe 1
        }
    }
}

private class RecordingReminderTokenFactory(
    private val result: String,
) : ReminderTokenFactory {
    var lastDueAt: LocalDateTime? = null
    var lastRepeatCount: Int? = null
    var lastIntervalMinutes: Int? = null
    var lastRecurrence: Recurrence? = null

    override fun buildInsertToken(
        dueAt: LocalDateTime,
        repeatCount: Int,
        intervalMinutes: Int,
        recurrence: Recurrence,
    ): String {
        lastDueAt = dueAt
        lastRepeatCount = repeatCount
        lastIntervalMinutes = intervalMinutes
        lastRecurrence = recurrence
        // Timestamp format is display wire for owner, not a second token grammar.
        dueAt.format(ReminderMarker.TIMESTAMP_FORMAT)
        return result
    }

    override fun planMarkDone(currentToken: String): String = error("not used")

    override fun planRecordFired(currentToken: String): String = error("not used")
}
