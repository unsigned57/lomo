/*
 * Behavior Contract:
 * - Unit under test: statistics aggregator
 * - Owning layer: application
 * - Priority tier: P2
 * - Capability: Pure bounded statistical aggregator computing memo metrics, period counts,
 *   streaks, hourly/weekly distributions, and daily time bounds matching Kotlin
 *   MemoStatisticsCalculator parity from immutable facts and an explicit CivilDate snapshot.
 *
 * Scenarios:
 * - Given an empty memo facts slice, when statistics are calculated, then true zero statistics
 *   are returned with empty distributions and absent time bounds.
 * - Given facts with UTF-16 code units, Chinese words, and tags, when aggregated, then counts
 *   are preserved verbatim and tags are deterministically counted and sorted.
 * - Given an instant crossing the new-year boundary, when evaluated in Shanghai and Tokyo, then
 *   their respective local dates, period counts, hourly buckets, and year bounds match their
 *   local timezone civil calendars.
 * - Given memos spanning ISO week and month boundaries, when aggregated, then thisWeek/lastWeek
 *   and thisMonth/lastMonth counts accurately reflect ISO Monday and calendar month starts.
 * - Given memos recorded on future dates beyond the asOf date, when aggregated, then future records
 *   are counted in current week/month/year periods but current streak is zero.
 * - Given memos on consecutive and discontinuous days, when streaks are computed, then current
 *   streak requires the last active day to be today or yesterday, and longest streak tracks maximum run.
 * - Given multiple memos on the same calendar day, when aggregated, then active days is 1, date count
 *   accumulates, and earliest/latest daily memo times bracket the day's span without inflating streaks.
 * - Given all memos belong to prior years with no memos in the current year, when aggregated, then
 *   earliest and latest daily memo times are None.
 * - Given non-positive epoch milliseconds or unknown IANA timezones, when statistics are requested,
 *   then explicit errors are returned instead of silent defaults or zero fallback.
 * - Given word or character counts whose sum overflows u64, when aggregated, then an explicit
 *   overflow error is returned.
 *
 * Observable outcomes:
 * - Ok(MemoStatistics) with correct counts, distributions, streaks, and time bounds.
 * - Err(StatisticsError) identifying invalid epochs, unknown zones, or arithmetic overflow.
 *
 * TDD proof:
 * - Fails initially because calculate_statistics is a stub returning Err(InvalidInput).
 *
 * Excludes:
 * - Filesystem I/O, database queries, SQLite connections, Kotlin JNI bridge, UI formatting.
 */

#[cfg(test)]
#[expect(
    clippy::cognitive_complexity,
    clippy::float_cmp,
    reason = "contract tests fail closed on missing facts"
)]
mod tests {
    use std::collections::BTreeMap;

    use lomo_application::{
        calendar::{CalendarError, CivilDate, CivilTime},
        statistics::{
            DayOfWeek, MemoTagCount, StatisticsError, StatisticsMemoFact, StatisticsSnapshot,
            calculate_statistics,
        },
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
    fn test_empty_set_returns_zero_statistics() {
        let as_of = test_ok(CivilDate::new(2026, 9, 9));
        let snapshot = StatisticsSnapshot::new("Asia/Shanghai", as_of);
        let facts: [StatisticsMemoFact; 0] = [];

        let stats = test_ok(calculate_statistics(&snapshot, &facts));

        assert_eq!(stats.as_of_date, as_of);
        assert_eq!(stats.total_memos, 0);
        assert_eq!(stats.total_words, 0);
        assert_eq!(stats.total_characters, 0);
        assert_eq!(stats.average_words_per_memo, 0.0);
        assert_eq!(stats.total_tags, 0);
        assert_eq!(stats.active_days, 0);
        assert_eq!(stats.current_streak, 0);
        assert_eq!(stats.longest_streak, 0);
        assert!(stats.memo_count_by_date.is_empty());
        assert!(stats.hourly_distribution.is_empty());
        assert!(stats.weekly_hour_distribution.is_empty());
        assert_eq!(stats.earliest_daily_memo_time, None);
        assert_eq!(stats.latest_daily_memo_time, None);
        assert_eq!(stats.this_week_count, 0);
        assert_eq!(stats.last_week_count, 0);
        assert_eq!(stats.this_month_count, 0);
        assert_eq!(stats.last_month_count, 0);
        assert_eq!(stats.this_year_count, 0);
        assert_eq!(stats.last_year_count, 0);
        assert!(stats.tag_counts.is_empty());
    }

    #[test]
    fn test_utf16_and_chinese_word_counts_aggregated_as_is() {
        // 2026-09-09T02:00:00Z = 1788919200000 ms (Shanghai: 2026-09-09 10:00:00)
        let as_of = test_ok(CivilDate::new(2026, 9, 9));
        let snapshot = StatisticsSnapshot::new("Asia/Shanghai", as_of);

        let fact1 = StatisticsMemoFact::new(
            1_788_919_200_000,
            12,
            45,
            vec!["rust".to_string(), "backend".to_string()],
        );
        // 2026-09-09T06:00:00Z = 1788933600000 ms (Shanghai: 2026-09-09 14:00:00)
        let fact2 = StatisticsMemoFact::new(
            1_788_933_600_000,
            8,
            35,
            vec!["backend".to_string(), "architecture".to_string()],
        );

        let stats = test_ok(calculate_statistics(&snapshot, &[fact1, fact2]));

        assert_eq!(stats.total_memos, 2);
        assert_eq!(stats.total_words, 20);
        assert_eq!(stats.total_characters, 80);
        assert_eq!(stats.average_words_per_memo, 10.0);
        assert_eq!(stats.total_tags, 3);

        let expected_tags = vec![
            MemoTagCount::new("backend", 2),
            MemoTagCount::new("architecture", 1),
            MemoTagCount::new("rust", 1),
        ];
        assert_eq!(stats.tag_counts, expected_tags);
    }

    #[test]
    fn test_tokyo_and_shanghai_new_year_timezone_attribution() {
        // UTC 2023-12-31T15:30:00Z = 1704036600000 ms
        // Shanghai (UTC+8): 2023-12-31 23:30:00 (Sunday)
        // Tokyo (UTC+9):    2024-01-01 00:30:00 (Monday)
        let epoch_ms = 1_704_036_600_000_i64;
        let as_of = test_ok(CivilDate::new(2024, 1, 1));

        let fact = StatisticsMemoFact::new(epoch_ms, 10, 50, vec!["holiday".to_string()]);

        // Evaluate in Tokyo: belongs to 2024-01-01 (Monday, 00:30)
        let tokyo_snapshot = StatisticsSnapshot::new("Asia/Tokyo", as_of);
        let tokyo_stats = test_ok(calculate_statistics(
            &tokyo_snapshot,
            std::slice::from_ref(&fact),
        ));
        assert_eq!(tokyo_stats.this_year_count, 1);
        assert_eq!(tokyo_stats.last_year_count, 0);
        assert_eq!(tokyo_stats.this_week_count, 1);
        assert_eq!(tokyo_stats.this_month_count, 1);
        assert_eq!(
            tokyo_stats.earliest_daily_memo_time,
            Some(test_ok(CivilTime::new(0, 30, 0)))
        );
        assert_eq!(
            tokyo_stats.latest_daily_memo_time,
            Some(test_ok(CivilTime::new(0, 30, 0)))
        );
        assert_eq!(tokyo_stats.current_streak, 1);
        assert_eq!(tokyo_stats.hourly_distribution.get(&0), Some(&1));
        let Some(monday_map) = tokyo_stats.weekly_hour_distribution.get(&DayOfWeek::Monday) else {
            panic!("missing monday distribution");
        };
        assert_eq!(monday_map.get(&0), Some(&1));

        // Evaluate in Shanghai: belongs to 2023-12-31 (Sunday, 23:30)
        let shanghai_snapshot = StatisticsSnapshot::new("Asia/Shanghai", as_of);
        let shanghai_stats = test_ok(calculate_statistics(
            &shanghai_snapshot,
            std::slice::from_ref(&fact),
        ));
        assert_eq!(shanghai_stats.this_year_count, 0);
        assert_eq!(shanghai_stats.last_year_count, 1);
        assert_eq!(shanghai_stats.this_month_count, 0);
        assert_eq!(shanghai_stats.last_month_count, 1);
        assert_eq!(shanghai_stats.this_week_count, 0);
        assert_eq!(shanghai_stats.last_week_count, 1);
        // In Shanghai, 2024 has NO memos, so daily memo time bounds are None
        assert_eq!(shanghai_stats.earliest_daily_memo_time, None);
        assert_eq!(shanghai_stats.latest_daily_memo_time, None);
        // 2023-12-31 is yesterday relative to 2024-01-01, so current_streak is 1
        assert_eq!(shanghai_stats.current_streak, 1);
        assert_eq!(shanghai_stats.hourly_distribution.get(&23), Some(&1));
        let Some(sunday_map) = shanghai_stats
            .weekly_hour_distribution
            .get(&DayOfWeek::Sunday)
        else {
            panic!("missing sunday distribution");
        };
        assert_eq!(sunday_map.get(&23), Some(&1));
    }

    #[test]
    fn test_iso_cross_week_and_month() {
        // 2024-03-31 12:00:00 UTC = 1711886400000 ms (Sunday, last day of March)
        // 2024-04-01 12:00:00 UTC = 1711972800000 ms (Monday, first day of April, new ISO week)
        let epoch_march_31 = 1_711_886_400_000_i64;
        let epoch_april_01 = 1_711_972_800_000_i64;

        let as_of = test_ok(CivilDate::new(2024, 4, 1));
        let snapshot = StatisticsSnapshot::new("UTC", as_of);

        let fact1 = StatisticsMemoFact::new(epoch_march_31, 5, 25, vec![]);
        let fact2 = StatisticsMemoFact::new(epoch_april_01, 7, 35, vec![]);

        let stats = test_ok(calculate_statistics(&snapshot, &[fact1, fact2]));

        assert_eq!(stats.this_week_count, 1);
        assert_eq!(stats.last_week_count, 1);
        assert_eq!(stats.this_month_count, 1);
        assert_eq!(stats.last_month_count, 1);
        assert_eq!(stats.this_year_count, 2);
        assert_eq!(stats.current_streak, 2);
        assert_eq!(stats.longest_streak, 2);
    }

    #[test]
    fn test_future_records_counted_in_periods_but_reset_current_streak() {
        // asOf is 2026-05-08 (Friday)
        // Memo 1: 2026-05-08 10:00:00 UTC = 1778234400000 ms
        // Memo 2: 2026-05-10 10:00:00 UTC = 1778407200000 ms (Sunday, future in same week)
        let as_of = test_ok(CivilDate::new(2026, 5, 8));
        let snapshot = StatisticsSnapshot::new("UTC", as_of);

        let fact1 = StatisticsMemoFact::new(1_778_234_400_000, 10, 50, vec![]);
        let fact2 = StatisticsMemoFact::new(1_778_407_200_000, 15, 60, vec![]);

        let stats = test_ok(calculate_statistics(&snapshot, &[fact1, fact2]));

        // Future dates >= week_start (2026-05-04) count in this_week
        assert_eq!(stats.this_week_count, 2);
        assert_eq!(stats.this_month_count, 2);
        assert_eq!(stats.this_year_count, 2);
        assert_eq!(stats.active_days, 2);
        // Last active date is 2026-05-10, which is neither today (05-08) nor yesterday (05-07)
        assert_eq!(stats.current_streak, 0);
        assert_eq!(stats.longest_streak, 1);
    }

    #[test]
    fn test_continuous_and_discontinuous_streaks() {
        // asOf is 2026-05-10 UTC
        let as_of = test_ok(CivilDate::new(2026, 5, 10));
        let snapshot = StatisticsSnapshot::new("UTC", as_of);

        // Run 1: 2026-05-01, 2026-05-02, 2026-05-03 (run of 3)
        // Gap on 2026-05-04
        // Run 2: 2026-05-05, 2026-05-06, 2026-05-07, 2026-05-08, 2026-05-09 (run of 5, ending yesterday)
        let timestamps = [
            1_777_593_600_000_i64, // 2026-05-01 00:00:00
            1_777_680_000_000_i64, // 2026-05-02 00:00:00
            1_777_766_400_000_i64, // 2026-05-03 00:00:00
            // gap 2026-05-04
            1_777_939_200_000_i64, // 2026-05-05 00:00:00
            1_778_025_600_000_i64, // 2026-05-06 00:00:00
            1_778_112_000_000_i64, // 2026-05-07 00:00:00
            1_778_198_400_000_i64, // 2026-05-08 00:00:00
            1_778_284_800_000_i64, // 2026-05-09 00:00:00
        ];

        let facts: Vec<StatisticsMemoFact> = timestamps
            .into_iter()
            .map(|ts| StatisticsMemoFact::new(ts, 1, 1, vec![]))
            .collect();

        let stats = test_ok(calculate_statistics(&snapshot, &facts));
        assert_eq!(stats.active_days, 8);
        assert_eq!(stats.current_streak, 5);
        assert_eq!(stats.longest_streak, 5);

        // If today is 2026-05-11, then the last memo (05-09) is 2 days ago, so current_streak resets to 0
        let as_of_later = test_ok(CivilDate::new(2026, 5, 11));
        let snapshot_later = StatisticsSnapshot::new("UTC", as_of_later);
        let stats_later = test_ok(calculate_statistics(&snapshot_later, &facts));
        assert_eq!(stats_later.current_streak, 0);
        assert_eq!(stats_later.longest_streak, 5);
    }

    #[test]
    fn test_multiple_memos_on_same_day() {
        let as_of = test_ok(CivilDate::new(2026, 5, 8));
        let snapshot = StatisticsSnapshot::new("UTC", as_of);

        // Three memos on 2026-05-08 at 09:15:00, 14:30:00, 21:45:00
        let f1 = StatisticsMemoFact::new(1_778_231_700_000, 10, 50, vec![]);
        let f2 = StatisticsMemoFact::new(1_778_250_600_000, 20, 100, vec![]);
        let f3 = StatisticsMemoFact::new(1_778_276_700_000, 30, 150, vec![]);

        let stats = test_ok(calculate_statistics(&snapshot, &[f1, f2, f3]));

        assert_eq!(stats.total_memos, 3);
        assert_eq!(stats.active_days, 1);
        let mut expected_date_counts = BTreeMap::new();
        expected_date_counts.insert(as_of, 3);
        assert_eq!(stats.memo_count_by_date, expected_date_counts);
        assert_eq!(stats.current_streak, 1);
        assert_eq!(stats.longest_streak, 1);
        assert_eq!(
            stats.earliest_daily_memo_time,
            Some(test_ok(CivilTime::new(9, 15, 0)))
        );
        assert_eq!(
            stats.latest_daily_memo_time,
            Some(test_ok(CivilTime::new(21, 45, 0)))
        );
    }

    #[test]
    fn test_empty_year_earliest_latest_none() {
        // Memos in 2025; as_of in 2026
        let as_of = test_ok(CivilDate::new(2026, 5, 8));
        let snapshot = StatisticsSnapshot::new("UTC", as_of);

        // 2025-12-31 10:00:00 UTC = 1767175200000 ms
        let fact = StatisticsMemoFact::new(1_767_175_200_000, 10, 50, vec![]);

        let stats = test_ok(calculate_statistics(&snapshot, std::slice::from_ref(&fact)));

        assert_eq!(stats.this_year_count, 0);
        assert_eq!(stats.last_year_count, 1);
        assert_eq!(stats.earliest_daily_memo_time, None);
        assert_eq!(stats.latest_daily_memo_time, None);
    }

    #[test]
    fn test_illegal_zone_and_time_explicit_error() {
        let as_of = test_ok(CivilDate::new(2026, 5, 8));

        // Unknown IANA zone
        let bad_zone_snapshot = StatisticsSnapshot::new("Mars/Olympus_Mons", as_of);
        let fact = StatisticsMemoFact::new(1_778_231_700_000, 10, 50, vec![]);
        let err_zone = test_err(calculate_statistics(&bad_zone_snapshot, &[fact]));
        assert_eq!(
            err_zone,
            StatisticsError::Calendar(CalendarError::UnknownTimeZone(
                "Mars/Olympus_Mons".to_string()
            ))
        );

        // Non-positive epoch ms (zero)
        let valid_snapshot = StatisticsSnapshot::new("UTC", as_of);
        let zero_epoch_fact = StatisticsMemoFact::new(0, 10, 50, vec![]);
        let err_zero = test_err(calculate_statistics(&valid_snapshot, &[zero_epoch_fact]));
        assert_eq!(
            err_zero,
            StatisticsError::Calendar(CalendarError::InvalidEpochMs(0))
        );

        // Non-positive epoch ms (negative)
        let neg_epoch_fact = StatisticsMemoFact::new(-1000, 10, 50, vec![]);
        let err_neg = test_err(calculate_statistics(&valid_snapshot, &[neg_epoch_fact]));
        assert_eq!(
            err_neg,
            StatisticsError::Calendar(CalendarError::InvalidEpochMs(-1000))
        );
    }

    #[test]
    fn test_arithmetic_overflow_rejection() {
        let as_of = test_ok(CivilDate::new(2026, 5, 8));
        let snapshot = StatisticsSnapshot::new("UTC", as_of);

        // Word count overflow
        let f1 = StatisticsMemoFact::new(1_778_231_700_000, u64::MAX, 10, vec![]);
        let f2 = StatisticsMemoFact::new(1_778_231_700_000, 1, 10, vec![]);
        let err_word = test_err(calculate_statistics(&snapshot, &[f1, f2]));
        assert!(matches!(err_word, StatisticsError::Overflow { .. }));

        // Char count overflow
        let f3 = StatisticsMemoFact::new(1_778_231_700_000, 10, u64::MAX, vec![]);
        let f4 = StatisticsMemoFact::new(1_778_231_700_000, 10, 1, vec![]);
        let err_char = test_err(calculate_statistics(&snapshot, &[f3, f4]));
        assert!(matches!(err_char, StatisticsError::Overflow { .. }));
    }
}
