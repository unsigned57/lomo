package com.lomo.data.engine.store

import com.lomo.data.reminder.toDomainRecurrence
import com.lomo.data.reminder.toNativeRecurrence
import com.lomo.data.reminder.toNativeSnoozeInterval
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.Recurrence
import com.lomo.domain.model.ReminderInterval
import com.lomo.nativebridge.ReminderRecurrence
import com.lomo.nativebridge.ReminderSnoozeInterval
import com.lomo.nativebridge.StoreMemoSummary
import com.lomo.nativebridge.WorkspaceReminderReference
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

/*
 * Behavior Contract:
 * - Unit under test: reminder value-domain parity across the native boundary.
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: recurrence and snooze interval are closed owner vocabularies — Kotlin maps each
 *   wire variant verbatim, stores no grammar codes or millisecond range checks, and every
 *   out-of-vocabulary wire value fails explicitly.
 *
 * Scenarios:
 * - Given the wire enums, when enumerated against the domain enums, then names and order match
 *   and every variant maps in both directions without loss.
 * - Given one reminder per recurrence variant in a memo summary, when mapped to the domain, then
 *   the marker carries the enum, verbatim dueAtLocal, and verbatim intervalMinutes.
 * - Given each snooze option, when decoded from stored millis, then the matching variant is
 *   produced; an out-of-vocabulary stored value throws.
 * - Given an out-of-vocabulary wire value, when decoded, then fromValue throws.
 *
 * Observable outcomes:
 * - Enum parity, per-variant marker facts, and explicit failures.
 *
 * TDD proof:
 * - RED before P-25: Kotlin parsed recurrenceCode strings via `fromCode`, duplicated
 *   SUPPORTED_MILLIS ranges, and repeated the grammar default `10`.
 *
 * Excludes:
 * - JNI token construction (lomo-workspace tests), DataStore I/O, and dialog rendering.
 */
class ReminderValueDomainParityTest : DataFunSpec() {
    init {
        test("given the recurrence wire enum when enumerated then domain names and order match") {
            ReminderRecurrence.entries.map { it.name } shouldContainExactly
                Recurrence.entries.map { it.name }
        }

        test("given each recurrence variant when mapped both ways then the mapping is total") {
            Recurrence.entries.forEach { domain ->
                domain.toNativeRecurrence().toDomainRecurrence() shouldBe domain
            }
            ReminderRecurrence.entries.forEach { wire ->
                wire.toDomainRecurrence() shouldBe Recurrence.valueOf(wire.name)
            }
        }

        test("given the snooze wire enum when enumerated then domain names and order match") {
            ReminderSnoozeInterval.entries.map { it.name } shouldContainExactly
                ReminderInterval.entries.map { it.name }
        }

        test("given each snooze option when mapped to wire and decoded from millis then it round-trips") {
            ReminderInterval.entries.forEach { domain ->
                domain.toNativeSnoozeInterval() shouldBe ReminderSnoozeInterval.valueOf(domain.name)
                ReminderInterval.fromMillis(domain.millis) shouldBe domain
            }
            ReminderInterval.DEFAULT shouldBe ReminderInterval.FIVE_MINUTES
            ReminderInterval.DEFAULT.millis shouldBe 5L * 60_000L
        }

        test("given an out-of-vocabulary stored interval when decoded then it throws explicitly") {
            shouldThrow<NoSuchElementException> {
                ReminderInterval.fromMillis(42L)
            }
        }

        test("given an out-of-vocabulary wire value when decoded then fromValue throws explicitly") {
            shouldThrow<NoSuchElementException> { ReminderRecurrence.fromValue(99) }
            shouldThrow<NoSuchElementException> { ReminderSnoozeInterval.fromValue(99) }
        }

        test("given reminders per variant when the summary maps then facts are verbatim typed") {
            val summary =
                memoSummary(
                    reminders =
                        listOf(
                            reminder(opaqueId = "r-none", recurrence = ReminderRecurrence.NONE),
                            reminder(opaqueId = "r-daily", recurrence = ReminderRecurrence.DAILY),
                            reminder(opaqueId = "r-weekly", recurrence = ReminderRecurrence.WEEKLY),
                        ),
                )

            val markers = summary.toStoreSummary().reminders

            markers.map { it.recurrence } shouldContainExactly
                listOf(Recurrence.NONE, Recurrence.DAILY, Recurrence.WEEKLY)
            markers.forEach { marker ->
                marker.dueAtLocal shouldBe "2026-07-20-09:30"
                marker.dueAt shouldBe LocalDateTime.of(2026, 7, 20, 9, 30)
                marker.intervalMinutes shouldBe 15
                marker.repeatCount shouldBe 2
            }
        }
    }
}

private fun memoSummary(reminders: List<WorkspaceReminderReference>): StoreMemoSummary =
    StoreMemoSummary(
        memoId = "memo-1",
        sourcePath = "2026-07-20_09:30:00_0.md",
        fileFingerprint = "fp",
        updatedAtMs = 20L,
        createdAtMs = 10L,
        hasTodo = false,
        hasUrl = false,
        hasAttachment = false,
        isPinned = false,
        isTrashed = false,
        bodyPreview = "body",
        contentRevision = 1uL,
        rank = null,
        tags = emptyList(),
        imageUrls = emptyList(),
        reminders = reminders,
        isPending = false,
        charCount = 4L,
    )

private fun reminder(
    opaqueId: String,
    recurrence: ReminderRecurrence,
): WorkspaceReminderReference =
    WorkspaceReminderReference(
        opaqueId = opaqueId,
        revision = "a".repeat(64),
        memoIdentity = "memo-1",
        sourceStart = 0uL,
        sourceEnd = 22uL,
        tokenFingerprint = "b".repeat(64),
        fingerprintOrdinal = 0u,
        embeddedId = null,
        token = "@2026-07-20-09:30x2i15",
        dueAtLocal = "2026-07-20-09:30",
        repeatCount = 2u,
        firedCount = 0u,
        done = false,
        intervalMinutes = 15u,
        recurrence = recurrence,
    )
