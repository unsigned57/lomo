package com.lomo.data.reminder

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.Memo
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.ReminderReference
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: reminderExecutionAllowed
 * - Owning layer: data reminder boundary
 * - Priority tier: P0
 * - Capability: prevent stale or non-durable memo rows from reaching OS notification effects.
 *
 * Scenarios:
 * - Given an active durable memo, when the alarm boundary checks it, then delivery is allowed.
 * - Given a trashed memo, when the alarm boundary checks it, then delivery is rejected.
 * - Given a pending memo, when the alarm boundary checks it, then delivery is rejected.
 *
 * Observable outcomes:
 * - The boundary policy returns the exact allow/reject decision.
 *
 * TDD proof:
 * - RED before the guard when the receiver treated every existing projection row as executable.
 *
 * Excludes:
 * - Android AlarmManager scheduling and notification rendering.
 */
class ReminderExecutionPolicyTest : DataFunSpec() {
    init {
        test("given active durable memo when alarm boundary checks it then delivery is allowed") {
            reminderExecutionAllowed(memo(isDeleted = false, isPending = false)) shouldBe true
        }

        test("given trashed memo when alarm boundary checks it then delivery is rejected") {
            reminderExecutionAllowed(memo(isDeleted = true, isPending = false)) shouldBe false
        }

        test("given pending memo when alarm boundary checks it then delivery is rejected") {
            reminderExecutionAllowed(memo(isDeleted = false, isPending = true)) shouldBe false
        }

        test("given a projected reminder when alarm identity is resolved then no workspace scan is needed") {
            val reminder =
                ReminderMarker(
                    dueAt = java.time.LocalDateTime.of(2026, 1, 1, 9, 0),
                    repeatCount = 1,
                    firedCount = 0,
                    done = false,
                    reference = ReminderReference(
                        opaqueId = "r1",
                        revision = "fp",
                        memoIdentity = "memo",
                        sourceSpan = MarkdownSourceSpan(0u, 1u),
                        tokenFingerprint = "tfp",
                    ),
                    token = "@2026-01-01-09:00",
                )
            projectedReminderFor(memo(isDeleted = false, isPending = false, reminders = listOf(reminder)), "r1") shouldBe reminder
            projectedReminderFor(memo(isDeleted = false, isPending = false, reminders = listOf(reminder)), "missing") shouldBe null
        }
    }

    private fun memo(
        isDeleted: Boolean,
        isPending: Boolean,
        reminders: List<ReminderMarker> = emptyList(),
    ): Memo =
        Memo(
            id = "memo",
            timestamp = 1L,
            content = "body",
            rawContent = "body",
            dateKey = "1970_01_01",
            isDeleted = isDeleted,
            isPending = isPending,
            reminders = reminders,
        )
}
