package com.lomo.data.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBePositive
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Behavior Contract:
 * - Unit under test: SAF projection chronology conversion.
 * - Owning layer: data native projection boundary.
 * - Priority tier: P0.
 *
 * - Capability: SAF projection chronology is derived from the canonical memo identity before store
 * rebuild; epoch zero and unparseable date/time components never enter the Rust projection.
 *
 * Scenarios:
 * - Given a supported date key and time part, when a scan summary becomes a SAF projection, then its
 * local timestamp is converted to a positive epoch millisecond in the device zone.
 * - Given an unsupported date key or time part, when conversion is attempted, then the boundary
 * rejects the summary instead of substituting zero.
 *
 * Observable outcomes:
 * - chronologyEpochMs or IllegalArgumentException.
 *
 * TDD proof:
 * - RED because SafMemoProjectionSnapshot initially had no chronology field.
 *
 * Excludes:
 * - Filesystem modified time for Direct workspaces.
 */
class SafProjectionChronologyTest :
    FunSpec({
        test("supported memo identity produces canonical local chronology") {
            val projection = summary(identity = "2026_08_02_19:30:00_0", timePart = "19:30:00")
                .toSafProjectionSnapshot()

            projection.chronologyEpochMs.shouldBePositive()
            projection.chronologyEpochMs shouldBe
                LocalDateTime
                    .of(2026, 8, 2, 19, 30)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
        }

        test("invalid date or time is rejected instead of becoming epoch zero") {
            shouldThrow<IllegalArgumentException> {
                summary(identity = "not-a-date_19:30:00_0", timePart = "19:30:00")
                    .toSafProjectionSnapshot()
            }
            shouldThrow<IllegalArgumentException> {
                summary(identity = "2026_08_02_not-a-time_0", timePart = "not-a-time")
                    .toSafProjectionSnapshot()
            }
        }
    })

private fun summary(identity: String, timePart: String): WorkspaceMemoSummarySnapshot =
    WorkspaceMemoSummarySnapshot(
        path = "2026_08_02.md",
        identity = identity,
        timePart = timePart,
        fingerprint = "f".repeat(64),
        tags = emptyList(),
        attachments = emptyList(),
        reminders = emptyList(),
        content = "body",
        bodyStart = 0uL,
        bodyEnd = 4uL,
        startLine = 1u,
        endLine = 1u,
    )
