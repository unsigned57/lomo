package com.lomo.ui.component.card

import com.lomo.domain.model.ReminderMarker
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * Behavior Contract:
 * - Unit under test: ReminderDisplayFormatter
 * - Owning layer: ui-components
 * - Priority tier: P2
 * - Capability: Format the display text of a reminder capsule shown in the memo card footer.
 *
 * Scenarios:
 * - Given an active reminder with a single alarm, when formatted, then the date-time is returned in "yyyy-MM-dd HH:mm" format.
 * - Given an active repeating reminder that has not fired, when formatted, then the date-time and repeat count suffix are returned.
 * - Given an active repeating reminder that has partially fired, when formatted, then the date-time, repeat suffix, and fraction (fired/repeat) are returned.
 * - Given an exhausted/done reminder, when formatted, then the date-time and done label suffix are returned.
 *
 * Observable outcomes:
 * - The formatted string matches the localized display pattern for the reminder capsule.
 *
 * TDD proof:
 * - Fails before implementation because the formatter returns an empty string.
 *
 * Excludes:
 * - Direct UI rendering on device, string resource resolution outside the test environment, haptic feedback triggers.
 */
class ReminderDisplayFormatterTest : UiComponentsFunSpec() {
    init {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val baseTime = LocalDateTime.of(2026, 5, 22, 17, 30)

        test("given an active reminder with a single alarm when formatted then it returns formatted date-time only") {
            val reminder = ReminderMarker(
                dueAt = baseTime,
                repeatCount = 1,
                firedCount = 0,
                done = false,
                tokenRange = IntRange.EMPTY,
                raw = ""
            )

            val display = ReminderDisplayFormatter.format(reminder, formatter, "done")
            display shouldBe "2026-05-22 17:30"
        }

        test("given an active repeating reminder that has not fired when formatted then it includes repeat suffix") {
            val reminder = ReminderMarker(
                dueAt = baseTime,
                repeatCount = 3,
                firedCount = 0,
                done = false,
                tokenRange = IntRange.EMPTY,
                raw = ""
            )

            val display = ReminderDisplayFormatter.format(reminder, formatter, "done")
            display shouldBe "2026-05-22 17:30 x3"
        }

        test("given an active repeating reminder that has partially fired when formatted then it includes repeat suffix and fire fraction") {
            val reminder = ReminderMarker(
                dueAt = baseTime,
                repeatCount = 3,
                firedCount = 1,
                done = false,
                tokenRange = IntRange.EMPTY,
                raw = ""
            )

            val display = ReminderDisplayFormatter.format(reminder, formatter, "done")
            display shouldBe "2026-05-22 17:30 x3 (1/3)"
        }

        test("given an exhausted reminder when formatted then it appends the done status suffix") {
            val reminder = ReminderMarker(
                dueAt = baseTime,
                repeatCount = 1,
                firedCount = 1,
                done = false,
                tokenRange = IntRange.EMPTY,
                raw = ""
            )

            val display = ReminderDisplayFormatter.format(reminder, formatter, "done")
            display shouldBe "2026-05-22 17:30 (done)"
        }
    }
}
