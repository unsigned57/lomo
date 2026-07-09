package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Recurrence
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/*
 * Behavior Contract:
 * - Unit under test: buildReminderToken
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: convert reminder dialog date, time, repeat, interval, and recurrence selections into the
 *   canonical reminder token inserted into memo content.
 *
 * Scenarios:
 * - Given repeat count, custom interval, and daily recurrence selections, when building the token, then the
 *   canonical xN/iM/rd suffix sequence is emitted.
 * - Given a one-shot weekly recurrence selection, when building the token, then the recurrence suffix is emitted
 *   without forcing a repeat-count or interval suffix.
 *
 * Observable outcomes:
 * - returned token string.
 *
 * TDD proof:
 * - Red phase fails before the fix because the dialog token-builder behavior for interval and recurrence choices
 *   is not available to an app-layer unit test.
 *
 * Excludes:
 * - DatePicker rendering, TimePicker rendering, localized labels, and domain parser internals.
 */
class ReminderInsertDialogTokenBuilderTest : AppFunSpec() {
    init {
        test("given repeat interval and daily recurrence when building token then canonical suffixes are emitted") {
            val token =
                buildReminderToken(
                    date = LocalDate.of(2026, 5, 22),
                    hour = 17,
                    minute = 51,
                    repeatCount = 5,
                    intervalMinutes = 15,
                    recurrence = Recurrence.DAILY,
                )

            token shouldBe "@2026-05-22-17:51x5i15rd"
        }

        test("given one-shot weekly recurrence when building token then recurrence suffix is preserved") {
            val token =
                buildReminderToken(
                    date = LocalDate.of(2026, 5, 22),
                    hour = 17,
                    minute = 51,
                    repeatCount = 1,
                    intervalMinutes = 30,
                    recurrence = Recurrence.WEEKLY,
                )

            token shouldBe "@2026-05-22-17:51rw"
        }
    }
}
