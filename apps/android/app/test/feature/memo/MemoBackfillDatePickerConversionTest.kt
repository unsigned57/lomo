package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/*
 * Behavior Contract:
 * Scenarios:
 * - Given a selected calendar date and a negative-offset device zone, when picker millis round-trip, then the
 *   UTC-anchored date does not shift.
 * - Given selected seconds, when the backfill date and time are converted or formatted, then the selected
 *   seconds are retained.
 * - Unit under test: Memo backfill DatePicker date conversion helpers.
 * - Capability: Material DatePicker UTC date millis must round-trip to the selected local date without
 *   being shifted by the device default time zone.
 * - Observable outcomes: converted epoch millis and LocalDate values.
 * - TDD proof: Fails before the fix because the helper contract is missing and the existing private
 *   conversion reads DatePicker UTC dates through the system default zone.
 * - Excludes: Compose dialog rendering, TimePicker UI behavior, and memo persistence.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for MemoBackfillDatePickerConversion.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including date picker millis are anchored to utc date
 * instead of device time zone; date picker selected date does not shift back one day in negative offsets.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class MemoBackfillDatePickerConversionTest : AppFunSpec() {
    private lateinit var originalTimeZone: TimeZone

    init {
        beforeTest {
            originalTimeZone = TimeZone.getDefault()
        }
    }

    init {
        afterTest {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    init {
        test("date picker millis are anchored to utc date instead of device time zone") {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val date = LocalDate.of(2026, 5, 5)

            val millis = date.toMemoBackfillDatePickerMillis()

            (millis) shouldBe (date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            (millis.toMemoBackfillLocalDate()) shouldBe (date)
        }
    }

    init {
        test("date picker selected date does not shift back one day in negative offsets") {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val selectedDateMillis =
                LocalDate
                    .of(2026, 1, 1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()

            (selectedDateMillis.toMemoBackfillLocalDate()) shouldBe (LocalDate.of(2026, 1, 1))
        }
    }

    init {
        test("backfill click opens picker for new memo regardless of current text") {
            ((shouldOpenMemoBackfillDialog(isEditingExistingMemo = false))) shouldBe true
            ((shouldOpenMemoBackfillDialog(isEditingExistingMemo = true))) shouldBe false
        }
    }

    init {
        test("backfill date time conversion preserves selected seconds") {
            val zone = ZoneId.of("Asia/Shanghai")
            val timestamp =
                combineMemoBackfillDateTimeMillis(
                    date = LocalDate.of(2026, 5, 5),
                    time = LocalTime.of(7, 8, 9),
                    zone = zone,
                )

            (timestamp) shouldBe (
                ZonedDateTime
                    .of(2026, 5, 5, 7, 8, 9, 0, zone)
                    .toInstant()
                    .toEpochMilli()
                )
        }
    }

    init {
        test("backfill badge formatter includes seconds even when app time format omits them") {
            val zone = ZoneId.of("Asia/Shanghai")
            val timestamp =
                ZonedDateTime
                    .of(2026, 5, 5, 7, 8, 9, 0, zone)
                    .toInstant()
                    .toEpochMilli()

            (
                formatMemoBackfillBadgeText(
                    timestampMillis = timestamp,
                    zone = zone,
                )
                ) shouldBe ("2026-05-05 07:08:09")
        }
    }
}
