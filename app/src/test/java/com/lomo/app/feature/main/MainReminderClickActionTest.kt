package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Recurrence
import com.lomo.domain.model.ReminderMarker
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

/*
 * Behavior Contract:
 * - Unit under test: createMainReminderDoneClickAction
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: bind a reminder pill click from a memo row to the main-screen mark-done action.
 *
 * Scenarios:
 * - Given a memo row with an active reminder marker, when the reminder click action runs, then the mark-done
 *   sink receives the row memo id and the marker's raw token.
 * - Given a marker with recurrence and interval suffixes, when the action runs, then the raw token is preserved
 *   instead of being rebuilt or normalized.
 *
 * Observable outcomes:
 * - recorded mark-done memo id and token raw values.
 *
 * TDD proof:
 * - Red phase fails before the fix because the main-list app layer has no tested reminder-click action binding,
 *   allowing the callback to stop at the memo card path without reaching MainViewModel.markReminderDone.
 *
 * Excludes:
 * - Compose gesture dispatch, haptic feedback, reminder scheduling, and repository implementation internals.
 */
class MainReminderClickActionTest : AppFunSpec() {
    init {
        test("given memo reminder when main list dispatches click then mark-done receives memo id and raw token") {
            val recorder = RecordingReminderDoneSink()
            val action =
                createMainReminderDoneClickAction(
                    memoId = "memo-42",
                    onReminderDone = recorder::markDone,
                )

            action(
                ReminderMarker(
                    dueAt = LocalDateTime.of(2026, 5, 22, 17, 51),
                    repeatCount = 5,
                    firedCount = 0,
                    done = false,
                    intervalMinutes = 15,
                    recurrence = Recurrence.WEEKLY,
                    tokenRange = 6..27,
                    raw = "@2026-05-22-17:51x5i15rw",
                ),
            )

            recorder.memoId shouldBe "memo-42"
            recorder.tokenRaw shouldBe "@2026-05-22-17:51x5i15rw"
            recorder.callCount shouldBe 1
        }
    }

    private class RecordingReminderDoneSink {
        var memoId: String? = null
            private set
        var tokenRaw: String? = null
            private set
        var callCount: Int = 0
            private set

        fun markDone(
            memoId: String,
            tokenRaw: String,
        ) {
            this.memoId = memoId
            this.tokenRaw = tokenRaw
            callCount += 1
        }
    }
}
