package com.lomo.data.reminder

import com.lomo.data.engine.store.StoreMemoSummary
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.ReminderReference
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: reminderExecutionAllowed / projectedReminderFor over the store projection
 *   summary the fire path actually consumes.
 * - Owning layer: data reminder boundary
 * - Priority tier: P0
 * - Capability: prevent stale or non-durable memo rows from reaching OS notification effects.
 *
 * Scenarios:
 * - Given an active durable memo summary, when the alarm boundary checks it, then delivery is allowed.
 * - Given a trashed memo summary, when the alarm boundary checks it, then delivery is rejected.
 * - Given a pending memo summary, when the alarm boundary checks it, then delivery is rejected.
 *
 * Observable outcomes:
 * - The boundary policy returns the exact allow/reject decision.
 *
 * TDD proof:
 * - RED before the guard when the receiver treated every existing projection row as executable.
 *
 * Excludes:
 * - Android AlarmManager scheduling and notification rendering.
 *
 * Test Change Justification:
 * - Reason category: boundary input type changed with the bodyPreview consumption fix.
 * - Old behavior/assertion being replaced: helpers consumed the full domain Memo read model.
 * - Why old assertion is no longer correct: the fire path now consumes StoreMemoSummary directly
 *   (bodyPreview + trashed/pending/reminders projection facts), so the policy locks that shape.
 * - Coverage preserved by: identical allow/reject scenarios, now over the projection row.
 * - Why this is not fitting the test to the implementation: the same allow/reject contract is
 *   asserted on the type the fire path actually reads, not on test-convenient domain objects.
 */
class ReminderExecutionPolicyTest : DataFunSpec() {
    init {
        test("given active durable memo when alarm boundary checks it then delivery is allowed") {
            reminderExecutionAllowed(summary(isTrashed = false, isPending = false)) shouldBe true
        }

        test("given trashed memo when alarm boundary checks it then delivery is rejected") {
            reminderExecutionAllowed(summary(isTrashed = true, isPending = false)) shouldBe false
        }

        test("given pending memo when alarm boundary checks it then delivery is rejected") {
            reminderExecutionAllowed(summary(isTrashed = false, isPending = true)) shouldBe false
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
            projectedReminderFor(summary(isTrashed = false, isPending = false, reminders = listOf(reminder)), "r1") shouldBe reminder
            projectedReminderFor(summary(isTrashed = false, isPending = false, reminders = listOf(reminder)), "missing") shouldBe null
        }
    }

    private fun summary(
        isTrashed: Boolean,
        isPending: Boolean,
        reminders: List<ReminderMarker> = emptyList(),
    ): StoreMemoSummary =
        StoreMemoSummary(
            memoId = "memo",
            sourcePath = "1970_01_01.md",
            fileFingerprint = "fp",
            updatedAtMs = 1L,
            createdAtMs = 1L,
            hasTodo = false,
            hasUrl = false,
            hasAttachment = false,
            isPinned = false,
            isTrashed = isTrashed,
            bodyPreview = "body",
            contentRevision = 1L,
            reminders = reminders,
            isPending = isPending,
        )
}
