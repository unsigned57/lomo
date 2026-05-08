package com.lomo.app.feature.memo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/*
 * Test Contract:
 * - Unit under test: Memo backfill DatePicker date conversion helpers.
 * - Behavior focus: Material DatePicker UTC date millis must round-trip to the selected local date without
 *   being shifted by the device default time zone.
 * - Observable outcomes: converted epoch millis and LocalDate values.
 * - Red phase: Fails before the fix because the helper contract is missing and the existing private
 *   conversion reads DatePicker UTC dates through the system default zone.
 * - Excludes: Compose dialog rendering, TimePicker UI behavior, and memo persistence.
 */
class MemoBackfillDatePickerConversionTest {
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun `date picker millis are anchored to utc date instead of device time zone`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        val date = LocalDate.of(2026, 5, 5)

        val millis = date.toMemoBackfillDatePickerMillis()

        assertEquals(
            date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            millis,
        )
        assertEquals(date, millis.toMemoBackfillLocalDate())
    }

    @Test
    fun `date picker selected date does not shift back one day in negative offsets`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        val selectedDateMillis =
            LocalDate
                .of(2026, 1, 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()

        assertEquals(LocalDate.of(2026, 1, 1), selectedDateMillis.toMemoBackfillLocalDate())
    }

    @Test
    fun `backfill click opens picker for new memo regardless of current text`() {
        assertTrue(shouldOpenMemoBackfillDialog(isEditingExistingMemo = false))
        assertFalse(shouldOpenMemoBackfillDialog(isEditingExistingMemo = true))
    }

    @Test
    fun `backfill date time conversion preserves selected seconds`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val timestamp =
            combineMemoBackfillDateTimeMillis(
                date = LocalDate.of(2026, 5, 5),
                time = LocalTime.of(7, 8, 9),
                zone = zone,
            )

        assertEquals(
            ZonedDateTime
                .of(2026, 5, 5, 7, 8, 9, 0, zone)
                .toInstant()
                .toEpochMilli(),
            timestamp,
        )
    }

    @Test
    fun `backfill badge formatter includes seconds even when app time format omits them`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val timestamp =
            ZonedDateTime
                .of(2026, 5, 5, 7, 8, 9, 0, zone)
                .toInstant()
                .toEpochMilli()

        assertEquals(
            "2026-05-05 07:08:09",
            formatMemoBackfillBadgeText(
                timestampMillis = timestamp,
                dateFormat = "yyyy-MM-dd",
                timeFormat = "HH:mm",
                zone = zone,
            ),
        )
    }
}
