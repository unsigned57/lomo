//! Pure bounded statistical aggregator for memo facts.
//!
//! Provides deterministic computation of memo counts, word and character counts,
//! active days, streaks, distributions, and time bounds without platform or UI dependencies.

use std::collections::BTreeMap;
use std::fmt;

use jiff::ToSpan;

use crate::calendar::{CalendarError, CivilDate, CivilTime};

/// Error conditions produced during statistics calculation.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum StatisticsError {
    /// Underlying calendar conversion or timezone failure.
    Calendar(CalendarError),
    /// Arithmetic overflow in counter, word, or character sums.
    Overflow { message: String },
    /// Invalid input parameter.
    InvalidInput { message: String },
}

impl fmt::Display for StatisticsError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Calendar(err) => write!(f, "calendar error: {err}"),
            Self::Overflow { message } => write!(f, "statistics overflow: {message}"),
            Self::InvalidInput { message } => write!(f, "invalid statistics input: {message}"),
        }
    }
}

impl std::error::Error for StatisticsError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Calendar(err) => Some(err),
            Self::Overflow { .. } | Self::InvalidInput { .. } => None,
        }
    }
}

impl From<CalendarError> for StatisticsError {
    fn from(err: CalendarError) -> Self {
        Self::Calendar(err)
    }
}

impl From<StatisticsError> for lomo_core::LomoError {
    fn from(err: StatisticsError) -> Self {
        match err {
            StatisticsError::Calendar(cal_err) => cal_err.into(),
            StatisticsError::Overflow { message } => {
                crate::error::validation("statistics_overflow", message)
            }
            StatisticsError::InvalidInput { message } => {
                crate::error::validation("invalid_statistics_input", message)
            }
        }
    }
}

/// Snapshot parameters for statistics aggregation.
#[derive(Clone, Debug, Eq, PartialEq, Hash)]
pub struct StatisticsSnapshot {
    /// IANA timezone identifier (e.g. "Asia/Shanghai", "Asia/Tokyo").
    pub zone: String,
    /// Reference civil date for streak and period calculations.
    pub as_of: CivilDate,
}

impl StatisticsSnapshot {
    /// Constructs a new snapshot.
    #[must_use]
    pub fn new(zone: impl Into<String>, as_of: CivilDate) -> Self {
        Self {
            zone: zone.into(),
            as_of,
        }
    }
}

/// Materialized projection facts for a single memo.
#[derive(Clone, Debug, Eq, PartialEq, Hash)]
pub struct StatisticsMemoFact {
    /// Memo creation epoch millisecond (strictly positive).
    pub created_at_ms: i64,
    /// Pre-counted word count.
    pub word_count: u64,
    /// Pre-counted character count in UTF-16 code units.
    pub char_count: u64,
    /// Deduplicated tags for this memo.
    pub tags: Vec<String>,
}

impl StatisticsMemoFact {
    /// Constructs a new memo fact.
    #[must_use]
    pub const fn new(
        created_at_ms: i64,
        word_count: u64,
        char_count: u64,
        tags: Vec<String>,
    ) -> Self {
        Self {
            created_at_ms,
            word_count,
            char_count,
            tags,
        }
    }
}

/// ISO weekday representation matching `java.time.DayOfWeek`.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub enum DayOfWeek {
    Monday = 1,
    Tuesday = 2,
    Wednesday = 3,
    Thursday = 4,
    Friday = 5,
    Saturday = 6,
    Sunday = 7,
}

impl DayOfWeek {
    /// Constructs a `DayOfWeek` from an ISO 1-7 weekday number (1 is Monday).
    ///
    /// # Errors
    /// Returns `StatisticsError::InvalidInput` if `val` is not in 1..=7.
    pub fn from_iso_weekday(val: u8) -> Result<Self, StatisticsError> {
        match val {
            1 => Ok(Self::Monday),
            2 => Ok(Self::Tuesday),
            3 => Ok(Self::Wednesday),
            4 => Ok(Self::Thursday),
            5 => Ok(Self::Friday),
            6 => Ok(Self::Saturday),
            7 => Ok(Self::Sunday),
            _ => Err(StatisticsError::InvalidInput {
                message: format!("invalid ISO weekday: {val}, expected 1..=7"),
            }),
        }
    }

    /// Returns the ISO weekday number (1 for Monday, 7 for Sunday).
    #[must_use]
    pub const fn iso_weekday(self) -> u8 {
        self as u8
    }
}

/// Materialized tag occurrence count.
#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct MemoTagCount {
    pub name: String,
    pub count: u64,
}

impl MemoTagCount {
    /// Constructs a new tag count entry.
    #[must_use]
    pub fn new(name: impl Into<String>, count: u64) -> Self {
        Self {
            name: name.into(),
            count,
        }
    }
}

/// Complete aggregated statistics matching Kotlin `MemoStatistics`.
#[derive(Clone, Debug, PartialEq)]
pub struct MemoStatistics {
    pub as_of_date: CivilDate,
    pub total_memos: u64,
    pub total_words: u64,
    pub total_characters: u64,
    pub average_words_per_memo: f64,
    pub total_tags: u64,
    pub active_days: u64,
    pub current_streak: u64,
    pub longest_streak: u64,
    pub memo_count_by_date: BTreeMap<CivilDate, u64>,
    pub hourly_distribution: BTreeMap<u8, u64>,
    pub weekly_hour_distribution: BTreeMap<DayOfWeek, BTreeMap<u8, u64>>,
    pub earliest_daily_memo_time: Option<CivilTime>,
    pub latest_daily_memo_time: Option<CivilTime>,
    pub this_week_count: u64,
    pub last_week_count: u64,
    pub this_month_count: u64,
    pub last_month_count: u64,
    pub this_year_count: u64,
    pub last_year_count: u64,
    pub tag_counts: Vec<MemoTagCount>,
}

fn civil_date_to_jiff(date: CivilDate) -> Result<jiff::civil::Date, StatisticsError> {
    let y = i16::try_from(date.year()).map_err(|e| StatisticsError::Overflow {
        message: e.to_string(),
    })?;
    let m = i8::try_from(date.month()).map_err(|e| StatisticsError::Overflow {
        message: e.to_string(),
    })?;
    let d = i8::try_from(date.day()).map_err(|e| StatisticsError::Overflow {
        message: e.to_string(),
    })?;
    jiff::civil::Date::new(y, m, d).map_err(|e| {
        StatisticsError::Calendar(CalendarError::InvalidDateKey { raw: e.to_string() })
    })
}

fn jiff_date_to_civil(date: jiff::civil::Date) -> Result<CivilDate, StatisticsError> {
    let m = u8::try_from(date.month()).map_err(|e| StatisticsError::Overflow {
        message: e.to_string(),
    })?;
    let d = u8::try_from(date.day()).map_err(|e| StatisticsError::Overflow {
        message: e.to_string(),
    })?;
    CivilDate::new(i32::from(date.year()), m, d).map_err(StatisticsError::Calendar)
}

struct PeriodBoundaries {
    this_week: CivilDate,
    last_week: CivilDate,
    this_month: CivilDate,
    last_month: CivilDate,
    this_year: CivilDate,
    next_year: CivilDate,
    last_year: CivilDate,
}

fn compute_period_boundaries(as_of: CivilDate) -> Result<PeriodBoundaries, StatisticsError> {
    let this_week = as_of.iso_monday()?;
    let week_start_j = civil_date_to_jiff(this_week)?;
    let last_week_start_j =
        week_start_j
            .checked_sub(7.days())
            .map_err(|e| StatisticsError::Overflow {
                message: e.to_string(),
            })?;
    let last_week = jiff_date_to_civil(last_week_start_j)?;

    let this_month = CivilDate::new(as_of.year(), as_of.month(), 1)?;
    let last_month = if as_of.month() == 1 {
        let prev_year = as_of
            .year()
            .checked_sub(1)
            .ok_or_else(|| StatisticsError::Overflow {
                message: "year underflow".to_string(),
            })?;
        CivilDate::new(prev_year, 12, 1)?
    } else {
        CivilDate::new(as_of.year(), as_of.month() - 1, 1)?
    };

    let this_year = CivilDate::new(as_of.year(), 1, 1)?;
    let next_year = CivilDate::new(
        as_of
            .year()
            .checked_add(1)
            .ok_or_else(|| StatisticsError::Overflow {
                message: "year overflow".to_string(),
            })?,
        1,
        1,
    )?;
    let last_year = CivilDate::new(
        as_of
            .year()
            .checked_sub(1)
            .ok_or_else(|| StatisticsError::Overflow {
                message: "year underflow".to_string(),
            })?,
        1,
        1,
    )?;

    Ok(PeriodBoundaries {
        this_week,
        last_week,
        this_month,
        last_month,
        this_year,
        next_year,
        last_year,
    })
}

#[derive(Default)]
struct AggregationAccumulator {
    total_words: u64,
    total_characters: u64,
    memo_count_by_date: BTreeMap<CivilDate, u64>,
    hourly_distribution: BTreeMap<u8, u64>,
    weekly_hour_distribution: BTreeMap<DayOfWeek, BTreeMap<u8, u64>>,
    this_week_count: u64,
    last_week_count: u64,
    this_month_count: u64,
    last_month_count: u64,
    this_year_count: u64,
    last_year_count: u64,
    earliest_daily_memo_time: Option<CivilTime>,
    latest_daily_memo_time: Option<CivilTime>,
    tag_map: BTreeMap<String, u64>,
}

impl AggregationAccumulator {
    fn process_fact(
        &mut self,
        fact: &StatisticsMemoFact,
        tz: &jiff::tz::TimeZone,
        bounds: &PeriodBoundaries,
    ) -> Result<(), StatisticsError> {
        self.total_words = self
            .total_words
            .checked_add(fact.word_count)
            .ok_or_else(|| StatisticsError::Overflow {
                message: "total_words overflow".to_string(),
            })?;
        self.total_characters = self
            .total_characters
            .checked_add(fact.char_count)
            .ok_or_else(|| StatisticsError::Overflow {
                message: "total_characters overflow".to_string(),
            })?;

        if fact.created_at_ms <= 0 {
            return Err(CalendarError::InvalidEpochMs(fact.created_at_ms).into());
        }

        let ts = jiff::Timestamp::from_millisecond(fact.created_at_ms).map_err(|err| {
            CalendarError::Overflow {
                message: err.to_string(),
            }
        })?;
        let zoned = ts.to_zoned(tz.clone());

        let j_date = zoned.date();
        let date = jiff_date_to_civil(j_date)?;
        let j_time = zoned.time();
        let hour = u8::try_from(j_time.hour()).map_err(|e| StatisticsError::Overflow {
            message: e.to_string(),
        })?;
        let minute = u8::try_from(j_time.minute()).map_err(|e| StatisticsError::Overflow {
            message: e.to_string(),
        })?;
        let second = u8::try_from(j_time.second()).map_err(|e| StatisticsError::Overflow {
            message: e.to_string(),
        })?;
        let civil_time = CivilTime::new(hour, minute, second)?;

        let weekday_num = date.weekday()?;
        let day_of_week = DayOfWeek::from_iso_weekday(weekday_num)?;

        let date_count = self.memo_count_by_date.entry(date).or_insert(0);
        *date_count = date_count
            .checked_add(1)
            .ok_or_else(|| StatisticsError::Overflow {
                message: "memo_count_by_date overflow".to_string(),
            })?;

        let hour_count = self.hourly_distribution.entry(hour).or_insert(0);
        *hour_count = hour_count
            .checked_add(1)
            .ok_or_else(|| StatisticsError::Overflow {
                message: "hourly_distribution overflow".to_string(),
            })?;

        let day_map = self
            .weekly_hour_distribution
            .entry(day_of_week)
            .or_default();
        let day_hour_count = day_map.entry(hour).or_insert(0);
        *day_hour_count =
            day_hour_count
                .checked_add(1)
                .ok_or_else(|| StatisticsError::Overflow {
                    message: "weekly_hour_distribution overflow".to_string(),
                })?;

        self.update_period_counts(date, civil_time, bounds)?;
        self.record_tags(&fact.tags)?;
        Ok(())
    }

    fn update_period_counts(
        &mut self,
        date: CivilDate,
        civil_time: CivilTime,
        bounds: &PeriodBoundaries,
    ) -> Result<(), StatisticsError> {
        if date >= bounds.this_week {
            self.this_week_count =
                self.this_week_count
                    .checked_add(1)
                    .ok_or_else(|| StatisticsError::Overflow {
                        message: "this_week_count overflow".to_string(),
                    })?;
        } else if date >= bounds.last_week {
            self.last_week_count =
                self.last_week_count
                    .checked_add(1)
                    .ok_or_else(|| StatisticsError::Overflow {
                        message: "last_week_count overflow".to_string(),
                    })?;
        }

        if date >= bounds.this_month {
            self.this_month_count =
                self.this_month_count
                    .checked_add(1)
                    .ok_or_else(|| StatisticsError::Overflow {
                        message: "this_month_count overflow".to_string(),
                    })?;
        } else if date >= bounds.last_month {
            self.last_month_count =
                self.last_month_count
                    .checked_add(1)
                    .ok_or_else(|| StatisticsError::Overflow {
                        message: "last_month_count overflow".to_string(),
                    })?;
        }

        if date >= bounds.this_year {
            self.this_year_count =
                self.this_year_count
                    .checked_add(1)
                    .ok_or_else(|| StatisticsError::Overflow {
                        message: "this_year_count overflow".to_string(),
                    })?;
        } else if date >= bounds.last_year {
            self.last_year_count =
                self.last_year_count
                    .checked_add(1)
                    .ok_or_else(|| StatisticsError::Overflow {
                        message: "last_year_count overflow".to_string(),
                    })?;
        }

        if date >= bounds.this_year && date < bounds.next_year {
            self.earliest_daily_memo_time = Some(
                self.earliest_daily_memo_time
                    .map_or(civil_time, |curr| curr.min(civil_time)),
            );
            self.latest_daily_memo_time = Some(
                self.latest_daily_memo_time
                    .map_or(civil_time, |curr| curr.max(civil_time)),
            );
        }
        Ok(())
    }

    fn record_tags(&mut self, tags: &[String]) -> Result<(), StatisticsError> {
        let mut memo_tags = tags.to_vec();
        memo_tags.sort();
        memo_tags.dedup();
        for tag in memo_tags {
            let count = self.tag_map.entry(tag).or_insert(0);
            *count = count
                .checked_add(1)
                .ok_or_else(|| StatisticsError::Overflow {
                    message: "tag count overflow".to_string(),
                })?;
        }
        Ok(())
    }
}

fn compute_streaks(
    sorted_dates: &[CivilDate],
    today: CivilDate,
) -> Result<(u64, u64), StatisticsError> {
    if sorted_dates.is_empty() {
        return Ok((0, 0));
    }

    let mut longest_streak = 1u64;
    let mut current_run = 1u64;

    for i in 1..sorted_dates.len() {
        let Some(&curr_date) = sorted_dates.get(i) else {
            break;
        };
        let prev_idx = i.saturating_sub(1);
        let Some(&prev_date) = sorted_dates.get(prev_idx) else {
            break;
        };

        let curr_j = civil_date_to_jiff(curr_date)?;
        let prev_j = curr_j.yesterday().map_err(|e| StatisticsError::Overflow {
            message: e.to_string(),
        })?;
        let prev_civil = jiff_date_to_civil(prev_j)?;

        if prev_civil == prev_date {
            current_run = current_run
                .checked_add(1)
                .ok_or_else(|| StatisticsError::Overflow {
                    message: "current_run overflow".to_string(),
                })?;
        } else {
            if current_run > longest_streak {
                longest_streak = current_run;
            }
            current_run = 1;
        }
    }
    if current_run > longest_streak {
        longest_streak = current_run;
    }

    let today_j = civil_date_to_jiff(today)?;
    let yesterday_j = today_j.yesterday().map_err(|e| StatisticsError::Overflow {
        message: e.to_string(),
    })?;
    let yesterday = jiff_date_to_civil(yesterday_j)?;

    let Some(&last_date) = sorted_dates.last() else {
        return Ok((0, 0));
    };
    let mut current_streak = 0u64;

    if last_date == today || last_date == yesterday {
        current_streak = 1;
        let mut idx = sorted_dates.len().saturating_sub(1);
        while idx > 0 {
            let prev_idx = idx.saturating_sub(1);
            let Some(&curr_date) = sorted_dates.get(idx) else {
                break;
            };
            let Some(&prev_date) = sorted_dates.get(prev_idx) else {
                break;
            };
            let curr_j = civil_date_to_jiff(curr_date)?;
            let prev_j = curr_j.yesterday().map_err(|e| StatisticsError::Overflow {
                message: e.to_string(),
            })?;
            let prev_civil = jiff_date_to_civil(prev_j)?;
            if prev_civil == prev_date {
                current_streak =
                    current_streak
                        .checked_add(1)
                        .ok_or_else(|| StatisticsError::Overflow {
                            message: "current_streak overflow".to_string(),
                        })?;
                idx = prev_idx;
            } else {
                break;
            }
        }
    }

    Ok((current_streak, longest_streak))
}

fn u64_to_f64(val: u64) -> f64 {
    let hi = u32::try_from(val >> 32).unwrap_or(0);
    let lo = u32::try_from(val & 0xFFFF_FFFF).unwrap_or(0);
    f64::from(hi).mul_add(4_294_967_296.0, f64::from(lo))
}

/// Calculates bounded memo statistics from immutable facts and a date snapshot.
///
/// # Errors
/// Returns [`StatisticsError`] if any timestamp or timezone is invalid, or if arithmetic overflows.
pub fn calculate_statistics(
    snapshot: &StatisticsSnapshot,
    facts: &[StatisticsMemoFact],
) -> Result<MemoStatistics, StatisticsError> {
    let tz = jiff::tz::TimeZone::get(&snapshot.zone)
        .map_err(|_err| CalendarError::UnknownTimeZone(snapshot.zone.clone()))?;
    if tz.is_unknown() {
        return Err(CalendarError::UnknownTimeZone(snapshot.zone.clone()).into());
    }

    let as_of_date = snapshot.as_of;
    let bounds = compute_period_boundaries(as_of_date)?;
    let mut acc = AggregationAccumulator::default();

    for fact in facts {
        acc.process_fact(fact, &tz, &bounds)?;
    }

    let mut tag_counts: Vec<MemoTagCount> = acc
        .tag_map
        .into_iter()
        .map(|(name, count)| MemoTagCount { name, count })
        .collect();
    tag_counts.sort_by(|a, b| b.count.cmp(&a.count).then_with(|| a.name.cmp(&b.name)));

    let total_tags = u64::try_from(tag_counts.len()).map_err(|e| StatisticsError::Overflow {
        message: e.to_string(),
    })?;

    let active_days =
        u64::try_from(acc.memo_count_by_date.len()).map_err(|e| StatisticsError::Overflow {
            message: e.to_string(),
        })?;

    let total_memos = u64::try_from(facts.len()).map_err(|e| StatisticsError::Overflow {
        message: e.to_string(),
    })?;

    let average_words_per_memo = if facts.is_empty() {
        0.0
    } else {
        u64_to_f64(acc.total_words) / u64_to_f64(total_memos)
    };

    let sorted_dates: Vec<CivilDate> = acc.memo_count_by_date.keys().copied().collect();
    let (current_streak, longest_streak) = compute_streaks(&sorted_dates, as_of_date)?;

    Ok(MemoStatistics {
        as_of_date,
        total_memos,
        total_words: acc.total_words,
        total_characters: acc.total_characters,
        average_words_per_memo,
        total_tags,
        active_days,
        current_streak,
        longest_streak,
        memo_count_by_date: acc.memo_count_by_date,
        hourly_distribution: acc.hourly_distribution,
        weekly_hour_distribution: acc.weekly_hour_distribution,
        earliest_daily_memo_time: acc.earliest_daily_memo_time,
        latest_daily_memo_time: acc.latest_daily_memo_time,
        this_week_count: acc.this_week_count,
        last_week_count: acc.last_week_count,
        this_month_count: acc.this_month_count,
        last_month_count: acc.last_month_count,
        this_year_count: acc.this_year_count,
        last_year_count: acc.last_year_count,
        tag_counts,
    })
}
