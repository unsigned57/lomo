/*
 * Behavior Contract:
 * - Unit under test: calendar
 * - Owning layer: application
 * - Priority tier: P2
 * - Capability: Pure Rust calendar and timezone primitives supporting strict date/time parsing,
 *   cross-pattern formatting, IANA timezone resolution, daylight saving time gap/overlap handling,
 *   and local day boundaries without platform or JNI dependencies.
 *
 * Scenarios:
 * - Given an IANA timezone and instant, when journal stamp is generated, then filename (.md) and
 *   HH:mm:ss token are derived from the identical instant across all 5 supported date formats.
 * - Given date keys with leap year 02-29 and invalid 02-30 across patterns, when parsed, then
 *   leap day succeeds and February 30 is rejected without fallback.
 * - Given single digit hours, 24:00, or invalid time components, when time token is parsed, then
 *   single digit hours succeed and 24:00 or malformed components are rejected.
 * - Given same UTC instant around new year midnight, when evaluated in Shanghai and Tokyo, then
 *   their respective local dates and day boundaries reflect distinct civil calendars accurately.
 * - Given a local time falling inside New York spring-forward gap (02:30:45), when memo chronology
 *   is computed, then it shifts by the full gap duration while preserving 30:45.
 * - Given an ambiguous local time during New York fall-back overlap, when memo chronology is
 *   computed, then the earlier instant is selected matching Kotlin LocalDateTime.atZone.
 * - Given 23-hour and 25-hour DST transition dates in New York, when day bounds are requested, then
 *   the half-open interval [start_ms, end_ms) matches 23 and 25 hours respectively.
 * - Given Lord Howe Island 30-minute DST transition, when a gap time is resolved, then the 30-minute
 *   offset shift is correctly handled.
 * - Given unknown timezone names or non-positive epoch milliseconds, when calendar operations run,
 *   then explicit errors are returned instead of silent defaults or panics.
 *
 * Observable outcomes:
 * - Ok(epoch_ms) for valid chronology timestamps matching Kotlin LocalDateTime.atZone.
 * - Ok(JournalStamp) with consistent filename and time token from a single instant.
 * - Ok((start_ms, end_ms)) exactly matching civil day boundaries under DST transitions.
 * - Err(CalendarError) identifying malformed dates, times, non-positive epochs, or unknown zones.
 *
 * TDD proof:
 * - Fails initially because calendar module primitives are stubs returning errors.
 *
 * Excludes:
 * - Statistics business aggregation, memo session persistence, platform local timezone inspection,
 *   JNI or Android NDK runtime calls.
 */

#[cfg(test)]
mod tests {
    use lomo_application::calendar::{
        CalendarError, CivilDate, CivilTime, DateFormat, JournalStamp, day_bounds, journal_stamp,
        local_date, memo_chronology, parse_date_key, parse_date_key_with_format, parse_pattern,
        parse_time_token,
    };

    fn test_ok<T, E: core::fmt::Debug>(result: Result<T, E>) -> T {
        match result {
            Ok(val) => val,
            Err(err) => panic!("expected Ok, got Err: {err:?}"),
        }
    }

    fn test_err<T: core::fmt::Debug, E>(result: Result<T, E>) -> E {
        match result {
            Ok(val) => panic!("expected Err, got Ok: {val:?}"),
            Err(err) => err,
        }
    }

    #[test]
    fn test_five_filenames_and_journal_stamp_same_instant() {
        // 2024-03-10T14:30:00Z = 1710081000000 ms
        let epoch_ms = 1_710_081_000_000_i64;
        let zone = "UTC";

        let stamp_underscore: JournalStamp = test_ok(journal_stamp(
            epoch_ms,
            zone,
            DateFormat::YyyyMmDdUnderscore,
        ));
        let stamp_hyphen: JournalStamp =
            test_ok(journal_stamp(epoch_ms, zone, DateFormat::YyyyMmDdHyphen));
        let stamp_dot: JournalStamp =
            test_ok(journal_stamp(epoch_ms, zone, DateFormat::YyyyMmDdDot));
        let stamp_compact: JournalStamp =
            test_ok(journal_stamp(epoch_ms, zone, DateFormat::YyyyMmDdCompact));
        let stamp_us: JournalStamp =
            test_ok(journal_stamp(epoch_ms, zone, DateFormat::MmDdYyyyHyphen));

        assert_eq!(stamp_underscore.filename, "2024_03_10.md");
        assert_eq!(stamp_hyphen.filename, "2024-03-10.md");
        assert_eq!(stamp_dot.filename, "2024.03.10.md");
        assert_eq!(stamp_compact.filename, "20240310.md");
        assert_eq!(stamp_us.filename, "03-10-2024.md");

        assert_eq!(stamp_underscore.time_token, "14:30:00");
        assert_eq!(stamp_hyphen.time_token, "14:30:00");
        assert_eq!(stamp_dot.time_token, "14:30:00");
        assert_eq!(stamp_compact.time_token, "14:30:00");
        assert_eq!(stamp_us.time_token, "14:30:00");

        // Parse date keys back and ensure they all resolve to 2024-03-10
        let date_underscore = test_ok(parse_date_key("2024_03_10"));
        let date_hyphen = test_ok(parse_date_key("2024-03-10"));
        let date_dot = test_ok(parse_date_key("2024.03.10"));
        let date_compact = test_ok(parse_date_key("20240310"));
        let date_us = test_ok(parse_date_key("03-10-2024"));

        assert_eq!(date_underscore.year(), 2024);
        assert_eq!(date_underscore.month(), 3);
        assert_eq!(date_underscore.day(), 10);
        assert_eq!(date_hyphen, date_underscore);
        assert_eq!(date_dot, date_underscore);
        assert_eq!(date_compact, date_underscore);
        assert_eq!(date_us, date_underscore);
    }

    #[test]
    fn test_date_key_leap_year_and_invalid_february_30() {
        // 2024 is a leap year; 02-29 is valid across patterns
        let d1: CivilDate = test_ok(parse_date_key("2024_02_29"));
        let d2: CivilDate = test_ok(parse_date_key("2024-02-29"));
        let d3: CivilDate = test_ok(parse_date_key("2024.02.29"));
        let d4: CivilDate = test_ok(parse_date_key("20240229"));
        let d5: CivilDate = test_ok(parse_date_key("02-29-2024"));
        let d_fmt: CivilDate = test_ok(parse_date_key_with_format(
            "2024_02_29",
            DateFormat::YyyyMmDdUnderscore,
        ));
        assert_eq!(d1, d_fmt);

        assert_eq!(d1.year(), 2024);
        assert_eq!(d1.month(), 2);
        assert_eq!(d1.day(), 29);
        assert_eq!(d1, d2);
        assert_eq!(d1, d3);
        assert_eq!(d1, d4);
        assert_eq!(d1, d5);

        // 2023 is not a leap year; 02-29 must be rejected
        let err_non_leap = test_err(parse_date_key("2023-02-29"));
        assert_eq!(
            err_non_leap,
            CalendarError::InvalidDateKey {
                raw: "2023-02-29".to_string(),
            }
        );

        // 2024-02-30 does not exist; all patterns must reject without fallback
        test_err(parse_date_key("2024_02_30"));
        test_err(parse_date_key("2024-02-30"));
        test_err(parse_date_key("2024.02.30"));
        test_err(parse_date_key("20240230"));
        test_err(parse_date_key("02-30-2024"));
    }

    #[test]
    fn test_time_token_single_digit_and_invalid_rejects_24_00() {
        let t1: CivilTime = test_ok(parse_time_token("9:05"));
        assert_eq!(t1.hour(), 9);
        assert_eq!(t1.minute(), 5);
        assert_eq!(t1.second(), 0);

        let t2: CivilTime = test_ok(parse_time_token("9:05:01"));
        assert_eq!(t2.hour(), 9);
        assert_eq!(t2.minute(), 5);
        assert_eq!(t2.second(), 1);

        let t3: CivilTime = test_ok(parse_time_token("09:05:01"));
        assert_eq!(t3.hour(), 9);
        assert_eq!(t3.minute(), 5);
        assert_eq!(t3.second(), 1);

        // 24:00 is not a valid 24-hour clock token
        test_err(parse_time_token("24:00"));
        test_err(parse_time_token("24:00:00"));

        // Invalid minute / second bounds
        test_err(parse_time_token("12:60"));
        test_err(parse_time_token("12:00:60"));
        test_err(parse_time_token(""));
        test_err(parse_time_token("12"));
        test_err(parse_time_token("12:00:00:00"));
        test_err(parse_time_token(" 12:00"));
        test_err(parse_time_token("12:00 "));
    }

    #[test]
    fn test_shanghai_and_tokyo_new_year_same_instant() {
        // UTC 2023-12-31T15:30:00Z = 1704036600000 ms
        // Shanghai (UTC+8): 2023-12-31 23:30:00
        // Tokyo (UTC+9):    2024-01-01 00:30:00
        let epoch_ms = 1_704_036_600_000_i64;

        let date_shanghai = test_ok(local_date(epoch_ms, "Asia/Shanghai"));
        assert_eq!(date_shanghai.year(), 2023);
        assert_eq!(date_shanghai.month(), 12);
        assert_eq!(date_shanghai.day(), 31);

        let date_tokyo = test_ok(local_date(epoch_ms, "Asia/Tokyo"));
        assert_eq!(date_tokyo.year(), 2024);
        assert_eq!(date_tokyo.month(), 1);
        assert_eq!(date_tokyo.day(), 1);

        let stamp_shanghai = test_ok(journal_stamp(
            epoch_ms,
            "Asia/Shanghai",
            DateFormat::YyyyMmDdHyphen,
        ));
        assert_eq!(stamp_shanghai.filename, "2023-12-31.md");
        assert_eq!(stamp_shanghai.time_token, "23:30:00");

        let stamp_tokyo = test_ok(journal_stamp(
            epoch_ms,
            "Asia/Tokyo",
            DateFormat::YyyyMmDdHyphen,
        ));
        assert_eq!(stamp_tokyo.filename, "2024-01-01.md");
        assert_eq!(stamp_tokyo.time_token, "00:30:00");
    }

    #[test]
    fn test_new_york_gap_preserves_minute_and_second() {
        // 2024-03-10: America/New_York jumps from 02:00 EST (-05:00) to 03:00 EDT (-04:00).
        // 02:30:45 falls into the gap.
        // Under Kotlin LocalDateTime.atZone / RFC 5545 compatible rules,
        // it shifts forward by the 1-hour gap to 03:30:45 EDT (-04:00) = 07:30:45 UTC.
        // 2024-03-10T07:30:45Z = 1710055845000 ms.
        let epoch_ms = test_ok(memo_chronology(
            "2024-03-10",
            "02:30:45",
            "America/New_York",
        ));
        assert_eq!(epoch_ms, 1_710_055_845_000_i64);

        // Verification from the resolved instant: local time is 03:30:45 (30:45 preserved)
        let stamp = test_ok(journal_stamp(
            epoch_ms,
            "America/New_York",
            DateFormat::YyyyMmDdHyphen,
        ));
        assert_eq!(stamp.filename, "2024-03-10.md");
        assert_eq!(stamp.time_token, "03:30:45");
    }

    #[test]
    fn test_new_york_overlap_picks_earlier_instant() {
        // 2024-11-03: America/New_York falls back from 02:00 EDT (-04:00) to 01:00 EST (-05:00).
        // 01:30:00 occurs twice:
        // Earlier occurrence: 01:30:00 EDT (-04:00) = 05:30:00 UTC = 1730611800000 ms.
        // Later occurrence:   01:30:00 EST (-05:00) = 06:30:00 UTC = 1730615400000 ms.
        // LocalDateTime.atZone selects the earlier instant.
        let epoch_ms = test_ok(memo_chronology(
            "2024-11-03",
            "01:30:00",
            "America/New_York",
        ));
        assert_eq!(epoch_ms, 1_730_611_800_000_i64);
    }

    #[test]
    fn test_new_york_day_bounds_23_and_25_hours() {
        // 23-hour DST spring-forward day: 2024-03-10
        let date_spring = test_ok(parse_date_key("2024-03-10"));
        let (start_spring, end_spring) = test_ok(day_bounds(date_spring, "America/New_York"));
        let duration_spring_ms = end_spring - start_spring;
        let expected_23_hours_ms = 23 * 3600 * 1000;
        assert_eq!(duration_spring_ms, expected_23_hours_ms);

        // 25-hour DST fall-back day: 2024-11-03
        let date_fall = test_ok(parse_date_key("2024-11-03"));
        let (start_fall, end_fall) = test_ok(day_bounds(date_fall, "America/New_York"));
        let duration_fall_ms = end_fall - start_fall;
        let expected_25_hours_ms = 25 * 3600 * 1000;
        assert_eq!(duration_fall_ms, expected_25_hours_ms);
    }

    #[test]
    fn test_lord_howe_30_minute_dst_gap() {
        // Australia/Lord_Howe has a 30-minute DST jump on 2024-10-06 from 02:00 (+10:30) to 02:30 (+11:00).
        // 02:15:00 falls into the 30-minute gap.
        // Shifting forward by 30 minutes yields 02:45:00 (+11:00).
        // 02:45:00 +11:00 = 2024-10-05T15:45:00Z = 1728143100000 ms.
        let epoch_ms = test_ok(memo_chronology(
            "2024-10-06",
            "02:15:00",
            "Australia/Lord_Howe",
        ));
        assert_eq!(epoch_ms, 1_728_143_100_000_i64);

        let stamp = test_ok(journal_stamp(
            epoch_ms,
            "Australia/Lord_Howe",
            DateFormat::YyyyMmDdHyphen,
        ));
        assert_eq!(stamp.time_token, "02:45:00");
    }

    #[test]
    fn test_explicit_errors_unknown_zone_and_non_positive_epoch() {
        let err_zone = test_err(local_date(1_710_081_000_000, "Mars/Olympus_Mons"));
        assert_eq!(
            err_zone,
            CalendarError::UnknownTimeZone("Mars/Olympus_Mons".to_string())
        );

        let err_epoch_zero = test_err(local_date(0, "UTC"));
        assert_eq!(err_epoch_zero, CalendarError::InvalidEpochMs(0));

        let err_epoch_neg = test_err(local_date(-5000, "UTC"));
        assert_eq!(err_epoch_neg, CalendarError::InvalidEpochMs(-5000));
    }

    #[test]
    fn test_statistics_helpers_pure_values() {
        // 2024-03-10 is a Sunday (ISO weekday 7)
        let date = test_ok(parse_date_key("2024-03-10"));
        assert_eq!(date.year(), 2024);
        assert_eq!(date.month(), 3);
        assert_eq!(date.day(), 10);
        assert_eq!(test_ok(date.weekday()), 7);

        // ISO Monday for week containing 2024-03-10 is 2024-03-04
        let monday = test_ok(date.iso_monday());
        assert_eq!(monday.year(), 2024);
        assert_eq!(monday.month(), 3);
        assert_eq!(monday.day(), 4);
        assert_eq!(test_ok(monday.weekday()), 1);
    }

    #[test]
    fn test_date_key_rejects_decorative_prefix_and_suffix() {
        test_err(parse_date_key(" 2024-03-10"));
        test_err(parse_date_key("2024-03-10.md"));
        test_err(parse_date_key("prefix-2024-03-10"));
        test_err(parse_date_key("2024-03-10-suffix"));
        test_err(parse_date_key(""));

        // Pattern parsing tests
        assert_eq!(
            test_ok(parse_pattern("yyyy_MM_dd")),
            DateFormat::YyyyMmDdUnderscore
        );
        assert_eq!(
            test_ok(parse_pattern("yyyy-MM-dd")),
            DateFormat::YyyyMmDdHyphen
        );
        assert_eq!(
            test_ok(parse_pattern("yyyy.MM.dd")),
            DateFormat::YyyyMmDdDot
        );
        assert_eq!(
            test_ok(parse_pattern("yyyyMMdd")),
            DateFormat::YyyyMmDdCompact
        );
        assert_eq!(
            test_ok(parse_pattern("MM-dd-yyyy")),
            DateFormat::MmDdYyyyHyphen
        );
        test_err(parse_pattern("dd/MM/yyyy"));
    }

    #[test]
    fn unknown_timezone_sentinel_is_not_accepted_as_utc() {
        let error = test_err(memo_chronology("2026_09_09", "09:00:00", "Etc/Unknown"));
        assert_eq!(
            error,
            CalendarError::UnknownTimeZone("Etc/Unknown".to_owned())
        );
    }

    #[test]
    fn midnight_gap_bounds_start_at_the_first_instant_of_the_day() {
        let date = test_ok(CivilDate::new(1919, 3, 31));
        let (start, end) = test_ok(day_bounds(date, "America/Toronto"));
        let expected = test_ok("1919-03-31T04:30:00Z".parse::<jiff::Timestamp>()).as_millisecond();
        let expected_end =
            test_ok("1919-04-01T04:00:00Z".parse::<jiff::Timestamp>()).as_millisecond();
        assert_eq!(start, expected);
        assert_eq!(end, expected_end);
    }
}
