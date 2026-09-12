//! Pure Rust calendar and timezone primitives for lomo-application.
//!
//! Provides strict parsing, formatting, and arithmetic for civil dates,
//! civil times, IANA timezones, and epoch millisecond chronologies conforming to
//! Kotlin `LocalDateTime.atZone` parity without platform or UI dependencies.

use std::fmt;

use jiff::{
    ToSpan,
    civil::{Date as JiffDate, Time as JiffTime},
    tz::TimeZone,
};

/// Supported storage date formats.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash, Default)]
pub enum DateFormat {
    /// Format: `yyyy_MM_dd` (default)
    #[default]
    YyyyMmDdUnderscore,
    /// Format: `yyyy-MM-dd`
    YyyyMmDdHyphen,
    /// Format: `yyyy.MM.dd`
    YyyyMmDdDot,
    /// Format: `yyyyMMdd`
    YyyyMmDdCompact,
    /// Format: `MM-dd-yyyy`
    MmDdYyyyHyphen,
}

impl DateFormat {
    /// All supported pattern strings in standard resolution order.
    pub const SUPPORTED_PATTERNS: [&'static str; 5] = [
        "yyyy_MM_dd",
        "yyyy-MM-dd",
        "yyyy.MM.dd",
        "yyyyMMdd",
        "MM-dd-yyyy",
    ];

    /// Returns the pattern string for this format.
    #[must_use]
    pub const fn pattern(self) -> &'static str {
        match self {
            Self::YyyyMmDdUnderscore => "yyyy_MM_dd",
            Self::YyyyMmDdHyphen => "yyyy-MM-dd",
            Self::YyyyMmDdDot => "yyyy.MM.dd",
            Self::YyyyMmDdCompact => "yyyyMMdd",
            Self::MmDdYyyyHyphen => "MM-dd-yyyy",
        }
    }
}

/// Calendar errors returned by pure date/timezone operations.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum CalendarError {
    /// Given epoch millisecond was not positive.
    InvalidEpochMs(i64),
    /// Given timezone name was not found in the IANA timezone database.
    UnknownTimeZone(String),
    /// Date format pattern string was not recognized.
    InvalidDateFormat { pattern: String },
    /// Date key did not match any supported format or contained invalid date values.
    InvalidDateKey { raw: String },
    /// Time token did not match supported formats or contained out-of-range values.
    InvalidTimeToken { raw: String },
    /// Daylight saving time disambiguation failed.
    DisambiguationFailed { message: String },
    /// Date or time calculation resulted in arithmetic overflow.
    Overflow { message: String },
}

impl fmt::Display for CalendarError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidEpochMs(ms) => write!(f, "invalid non-positive epoch millisecond: {ms}"),
            Self::UnknownTimeZone(zone) => write!(f, "unknown IANA timezone: {zone}"),
            Self::InvalidDateFormat { pattern } => {
                write!(f, "invalid date format pattern: {pattern}")
            }
            Self::InvalidDateKey { raw } => write!(f, "invalid date key: {raw}"),
            Self::InvalidTimeToken { raw } => write!(f, "invalid time token: {raw}"),
            Self::DisambiguationFailed { message } => {
                write!(f, "disambiguation failed: {message}")
            }
            Self::Overflow { message } => write!(f, "calendar arithmetic overflow: {message}"),
        }
    }
}

impl std::error::Error for CalendarError {}

impl From<CalendarError> for lomo_core::LomoError {
    fn from(err: CalendarError) -> Self {
        crate::error::validation("invalid_calendar_input", err.to_string())
    }
}

/// Pure civil date (year, month, day) in the proleptic Gregorian calendar.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct CivilDate {
    year: i32,
    month: u8,
    day: u8,
}

impl CivilDate {
    /// Constructs a validated civil date.
    ///
    /// # Errors
    ///
    /// Returns [`CalendarError::InvalidDateKey`] if the values do not represent
    /// a valid calendar date (e.g. invalid month, day out of range, non-leap Feb 29).
    pub fn new(year: i32, month: u8, day: u8) -> Result<Self, CalendarError> {
        let (y_i16, m_i8, d_i8) = check_date_components(year, month, day)?;
        JiffDate::new(y_i16, m_i8, d_i8).map_err(|_err| CalendarError::InvalidDateKey {
            raw: format!("{year:04}-{month:02}-{day:02}"),
        })?;
        Ok(Self { year, month, day })
    }

    fn from_jiff(date: JiffDate) -> Result<Self, CalendarError> {
        let month = u8::try_from(date.month()).map_err(|error| CalendarError::Overflow {
            message: error.to_string(),
        })?;
        let day = u8::try_from(date.day()).map_err(|error| CalendarError::Overflow {
            message: error.to_string(),
        })?;
        Self::new(i32::from(date.year()), month, day)
    }

    fn to_jiff(self) -> Result<JiffDate, CalendarError> {
        let (year_i16, month_i8, day_i8) = check_date_components(self.year, self.month, self.day)?;
        JiffDate::new(year_i16, month_i8, day_i8).map_err(|err| CalendarError::Overflow {
            message: err.to_string(),
        })
    }

    /// Astronomical / civil year.
    #[must_use]
    pub const fn year(&self) -> i32 {
        self.year
    }

    /// Calendar month (1..=12).
    #[must_use]
    pub const fn month(&self) -> u8 {
        self.month
    }

    /// Day of the month (1..=31).
    #[must_use]
    pub const fn day(&self) -> u8 {
        self.day
    }

    /// ISO weekday number where 1 is Monday and 7 is Sunday.
    ///
    /// # Errors
    /// Returns an error if the civil date cannot be represented by the calendar engine.
    pub fn weekday(&self) -> Result<u8, CalendarError> {
        Ok(match self.to_jiff()?.weekday() {
            jiff::civil::Weekday::Monday => 1,
            jiff::civil::Weekday::Tuesday => 2,
            jiff::civil::Weekday::Wednesday => 3,
            jiff::civil::Weekday::Thursday => 4,
            jiff::civil::Weekday::Friday => 5,
            jiff::civil::Weekday::Saturday => 6,
            jiff::civil::Weekday::Sunday => 7,
        })
    }

    /// Computes the Monday of the ISO week containing this date.
    ///
    /// # Errors
    /// Propagates calendar bounds and arithmetic failures.
    pub fn iso_monday(&self) -> Result<Self, CalendarError> {
        let days_back = i64::from(self.weekday()?) - 1;
        let monday = self
            .to_jiff()?
            .checked_sub(days_back.days())
            .map_err(|error| CalendarError::Overflow {
                message: error.to_string(),
            })?;
        Self::from_jiff(monday)
    }
}

/// Pure civil time (hour, minute, second).
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct CivilTime {
    hour: u8,
    minute: u8,
    second: u8,
}

impl CivilTime {
    /// Constructs a validated civil time.
    ///
    /// # Errors
    ///
    /// Returns [`CalendarError::InvalidTimeToken`] if hour >= 24, minute >= 60,
    /// or second >= 60.
    pub fn new(hour: u8, minute: u8, second: u8) -> Result<Self, CalendarError> {
        let (hour_i8, minute_i8, second_i8) = check_time_components(hour, minute, second)?;
        JiffTime::new(hour_i8, minute_i8, second_i8, 0).map_err(|_err| {
            CalendarError::InvalidTimeToken {
                raw: format!("{hour:02}:{minute:02}:{second:02}"),
            }
        })?;
        Ok(Self {
            hour,
            minute,
            second,
        })
    }

    /// Hour of the day (0..=23).
    #[must_use]
    pub const fn hour(&self) -> u8 {
        self.hour
    }

    /// Minute of the hour (0..=59).
    #[must_use]
    pub const fn minute(&self) -> u8 {
        self.minute
    }

    /// Second of the minute (0..=59).
    #[must_use]
    pub const fn second(&self) -> u8 {
        self.second
    }
}

/// Journal file and timestamp representation formatted from a single instant.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct JournalStamp {
    /// Formatted Markdown filename including `.md` extension.
    pub filename: String,
    /// Formatted time token (`HH:mm:ss`).
    pub time_token: String,
}

impl JournalStamp {
    /// Returns the filename and time token as a tuple.
    #[must_use]
    pub fn parts(self) -> (String, String) {
        (self.filename, self.time_token)
    }
}

fn check_date_components(year: i32, month: u8, day: u8) -> Result<(i16, i8, i8), CalendarError> {
    if !(1..=9999).contains(&year) || !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return Err(CalendarError::InvalidDateKey {
            raw: format!("{year:04}-{month:02}-{day:02}"),
        });
    }
    let checked_year = match i16::try_from(year) {
        Ok(val) => val,
        Err(_err) => {
            return Err(CalendarError::Overflow {
                message: format!("year {year} exceeds i16 bounds"),
            });
        }
    };
    let checked_month = match i8::try_from(month) {
        Ok(val) => val,
        Err(_err) => {
            return Err(CalendarError::Overflow {
                message: format!("month {month} exceeds i8 bounds"),
            });
        }
    };
    let checked_day = match i8::try_from(day) {
        Ok(val) => val,
        Err(_err) => {
            return Err(CalendarError::Overflow {
                message: format!("day {day} exceeds i8 bounds"),
            });
        }
    };
    Ok((checked_year, checked_month, checked_day))
}

fn check_time_components(hour: u8, minute: u8, second: u8) -> Result<(i8, i8, i8), CalendarError> {
    if hour > 23 || minute > 59 || second > 59 {
        return Err(CalendarError::InvalidTimeToken {
            raw: format!("{hour:02}:{minute:02}:{second:02}"),
        });
    }
    let checked_hour = match i8::try_from(hour) {
        Ok(val) => val,
        Err(_err) => {
            return Err(CalendarError::Overflow {
                message: format!("hour {hour} exceeds i8 bounds"),
            });
        }
    };
    let checked_minute = match i8::try_from(minute) {
        Ok(val) => val,
        Err(_err) => {
            return Err(CalendarError::Overflow {
                message: format!("minute {minute} exceeds i8 bounds"),
            });
        }
    };
    let checked_second = match i8::try_from(second) {
        Ok(val) => val,
        Err(_err) => {
            return Err(CalendarError::Overflow {
                message: format!("second {second} exceeds i8 bounds"),
            });
        }
    };
    Ok((checked_hour, checked_minute, checked_second))
}

fn parse_2_digits(bytes: &[u8], offset: usize) -> Option<u8> {
    let next_offset = offset.checked_add(1)?;
    let d1 = *bytes.get(offset)?;
    let d2 = *bytes.get(next_offset)?;
    if d1.is_ascii_digit() && d2.is_ascii_digit() {
        Some((d1 - b'0') * 10 + (d2 - b'0'))
    } else {
        None
    }
}

fn parse_4_digits(bytes: &[u8], offset: usize) -> Option<i32> {
    let o1 = offset.checked_add(1)?;
    let o2 = offset.checked_add(2)?;
    let o3 = offset.checked_add(3)?;
    let d1 = *bytes.get(offset)?;
    let d2 = *bytes.get(o1)?;
    let d3 = *bytes.get(o2)?;
    let d4 = *bytes.get(o3)?;
    if d1.is_ascii_digit() && d2.is_ascii_digit() && d3.is_ascii_digit() && d4.is_ascii_digit() {
        let val = i32::from(d1 - b'0') * 1000
            + i32::from(d2 - b'0') * 100
            + i32::from(d3 - b'0') * 10
            + i32::from(d4 - b'0');
        Some(val)
    } else {
        None
    }
}

fn parse_date_with_format(raw: &str, format: DateFormat) -> Result<CivilDate, CalendarError> {
    let bytes = raw.as_bytes();
    let (year, month, day) = match format {
        DateFormat::YyyyMmDdUnderscore => parse_date_delimited(bytes, raw, b'_')?,
        DateFormat::YyyyMmDdHyphen => parse_date_delimited(bytes, raw, b'-')?,
        DateFormat::YyyyMmDdDot => parse_date_delimited(bytes, raw, b'.')?,
        DateFormat::YyyyMmDdCompact => parse_date_compact(bytes, raw)?,
        DateFormat::MmDdYyyyHyphen => parse_date_mm_dd_yyyy(bytes, raw)?,
    };
    CivilDate::new(year, month, day)
}

fn parse_date_delimited(
    bytes: &[u8],
    raw: &str,
    delimiter: u8,
) -> Result<(i32, u8, u8), CalendarError> {
    if bytes.len() != 10 || bytes.get(4) != Some(&delimiter) || bytes.get(7) != Some(&delimiter) {
        return Err(CalendarError::InvalidDateKey {
            raw: raw.to_string(),
        });
    }
    let year = parse_4_digits(bytes, 0).ok_or_else(|| CalendarError::InvalidDateKey {
        raw: raw.to_string(),
    })?;
    let month = parse_2_digits(bytes, 5).ok_or_else(|| CalendarError::InvalidDateKey {
        raw: raw.to_string(),
    })?;
    let day = parse_2_digits(bytes, 8).ok_or_else(|| CalendarError::InvalidDateKey {
        raw: raw.to_string(),
    })?;
    Ok((year, month, day))
}

fn parse_date_compact(bytes: &[u8], raw: &str) -> Result<(i32, u8, u8), CalendarError> {
    if bytes.len() != 8 {
        return Err(CalendarError::InvalidDateKey {
            raw: raw.to_string(),
        });
    }
    let year = parse_4_digits(bytes, 0).ok_or_else(|| CalendarError::InvalidDateKey {
        raw: raw.to_string(),
    })?;
    let month = parse_2_digits(bytes, 4).ok_or_else(|| CalendarError::InvalidDateKey {
        raw: raw.to_string(),
    })?;
    let day = parse_2_digits(bytes, 6).ok_or_else(|| CalendarError::InvalidDateKey {
        raw: raw.to_string(),
    })?;
    Ok((year, month, day))
}

fn parse_date_mm_dd_yyyy(bytes: &[u8], raw: &str) -> Result<(i32, u8, u8), CalendarError> {
    if bytes.len() != 10 || bytes.get(2) != Some(&b'-') || bytes.get(5) != Some(&b'-') {
        return Err(CalendarError::InvalidDateKey {
            raw: raw.to_string(),
        });
    }
    let month = parse_2_digits(bytes, 0).ok_or_else(|| CalendarError::InvalidDateKey {
        raw: raw.to_string(),
    })?;
    let day = parse_2_digits(bytes, 3).ok_or_else(|| CalendarError::InvalidDateKey {
        raw: raw.to_string(),
    })?;
    let year = parse_4_digits(bytes, 6).ok_or_else(|| CalendarError::InvalidDateKey {
        raw: raw.to_string(),
    })?;
    Ok((year, month, day))
}

/// Parses a date format pattern string into a [`DateFormat`] enum.
///
/// # Errors
///
/// Returns [`CalendarError::InvalidDateFormat`] if the pattern is unknown.
pub fn parse_pattern(pattern: &str) -> Result<DateFormat, CalendarError> {
    match pattern {
        "yyyy_MM_dd" => Ok(DateFormat::YyyyMmDdUnderscore),
        "yyyy-MM-dd" => Ok(DateFormat::YyyyMmDdHyphen),
        "yyyy.MM.dd" => Ok(DateFormat::YyyyMmDdDot),
        "yyyyMMdd" => Ok(DateFormat::YyyyMmDdCompact),
        "MM-dd-yyyy" => Ok(DateFormat::MmDdYyyyHyphen),
        _ => Err(CalendarError::InvalidDateFormat {
            pattern: pattern.to_string(),
        }),
    }
}

/// Formats a [`CivilDate`] according to the given [`DateFormat`].
#[must_use]
pub fn format_date_key(date: CivilDate, format: DateFormat) -> String {
    match format {
        DateFormat::YyyyMmDdUnderscore => {
            format!("{:04}_{:02}_{:02}", date.year(), date.month(), date.day())
        }
        DateFormat::YyyyMmDdHyphen => {
            format!("{:04}-{:02}-{:02}", date.year(), date.month(), date.day())
        }
        DateFormat::YyyyMmDdDot => {
            format!("{:04}.{:02}.{:02}", date.year(), date.month(), date.day())
        }
        DateFormat::YyyyMmDdCompact => {
            format!("{:04}{:02}{:02}", date.year(), date.month(), date.day())
        }
        DateFormat::MmDdYyyyHyphen => {
            format!("{:02}-{:02}-{:04}", date.month(), date.day(), date.year())
        }
    }
}

/// Formats a [`CivilDate`] into a markdown filename (e.g. `2024_03_10.md`).
#[must_use]
pub fn format_filename(date: CivilDate, format: DateFormat) -> String {
    format!("{}.md", format_date_key(date, format))
}

/// Parses a raw date key against a specific [`DateFormat`].
///
/// # Errors
///
/// Returns [`CalendarError::InvalidDateKey`] if the string does not match the format
/// or fails calendar validation.
pub fn parse_date_key_with_format(
    raw: &str,
    format: DateFormat,
) -> Result<CivilDate, CalendarError> {
    parse_date_with_format(raw, format)
}

/// Strictly parses a raw date key by trying all supported formats in sequence.
///
/// Rejects any decorative prefixes, suffixes, or invalid date values.
///
/// # Errors
///
/// Returns [`CalendarError::InvalidDateKey`] if the key does not match any supported format.
pub fn parse_date_key(raw: &str) -> Result<CivilDate, CalendarError> {
    for &format in &[
        DateFormat::YyyyMmDdUnderscore,
        DateFormat::YyyyMmDdHyphen,
        DateFormat::YyyyMmDdDot,
        DateFormat::YyyyMmDdCompact,
        DateFormat::MmDdYyyyHyphen,
    ] {
        if let Ok(date) = parse_date_with_format(raw, format) {
            return Ok(date);
        }
    }
    Err(CalendarError::InvalidDateKey {
        raw: raw.to_string(),
    })
}

/// Strictly parses a time token supporting `H:mm`, `HH:mm`, `H:mm:ss`, and `HH:mm:ss`.
///
/// # Errors
///
/// Returns [`CalendarError::InvalidTimeToken`] on `24:00`, invalid minute/second, or
/// malformed syntax.
pub fn parse_time_token(raw: &str) -> Result<CivilTime, CalendarError> {
    let parts: Vec<&str> = raw.split(':').collect();
    if parts.len() != 2 && parts.len() != 3 {
        return Err(CalendarError::InvalidTimeToken {
            raw: raw.to_string(),
        });
    }

    let hour_str = parts
        .first()
        .ok_or_else(|| CalendarError::InvalidTimeToken {
            raw: raw.to_owned(),
        })?;
    let hour_bytes = hour_str.as_bytes();
    let hour: u8 = match hour_bytes.len() {
        1 => {
            let digit = *hour_bytes
                .first()
                .ok_or_else(|| CalendarError::InvalidTimeToken {
                    raw: raw.to_string(),
                })?;
            if !digit.is_ascii_digit() {
                return Err(CalendarError::InvalidTimeToken {
                    raw: raw.to_string(),
                });
            }
            digit - b'0'
        }
        2 => parse_2_digits(hour_bytes, 0).ok_or_else(|| CalendarError::InvalidTimeToken {
            raw: raw.to_string(),
        })?,
        _ => {
            return Err(CalendarError::InvalidTimeToken {
                raw: raw.to_string(),
            });
        }
    };

    let minute_str = parts
        .get(1)
        .ok_or_else(|| CalendarError::InvalidTimeToken {
            raw: raw.to_owned(),
        })?;
    let minute_bytes = minute_str.as_bytes();
    if minute_bytes.len() != 2 {
        return Err(CalendarError::InvalidTimeToken {
            raw: raw.to_string(),
        });
    }
    let minute =
        parse_2_digits(minute_bytes, 0).ok_or_else(|| CalendarError::InvalidTimeToken {
            raw: raw.to_string(),
        })?;

    let second: u8 = if let Some(sec_str) = parts.get(2).copied() {
        let sec_bytes = sec_str.as_bytes();
        if sec_bytes.len() != 2 {
            return Err(CalendarError::InvalidTimeToken {
                raw: raw.to_string(),
            });
        }
        parse_2_digits(sec_bytes, 0).ok_or_else(|| CalendarError::InvalidTimeToken {
            raw: raw.to_string(),
        })?
    } else {
        0
    };

    CivilTime::new(hour, minute, second)
}

fn resolve_zone(zone: &str) -> Result<TimeZone, CalendarError> {
    let timezone =
        TimeZone::get(zone).map_err(|_err| CalendarError::UnknownTimeZone(zone.to_owned()))?;
    if timezone.is_unknown() {
        return Err(CalendarError::UnknownTimeZone(zone.to_owned()));
    }
    Ok(timezone)
}

/// Resolves a memo's date key, time token, and IANA timezone to a positive epoch millisecond.
///
/// Conforms to Kotlin `LocalDateTime.atZone` behavior:
/// - Overlap: selects the earlier instant.
/// - Gap: shifts forward by the full gap duration while preserving minute and second.
///
/// # Errors
///
/// Returns [`CalendarError`] if inputs are invalid, timezone is unknown, or the resulting
/// epoch millisecond is not strictly positive.
pub fn memo_chronology(date_key: &str, time_token: &str, zone: &str) -> Result<i64, CalendarError> {
    let date = parse_date_key(date_key)?;
    let time = parse_time_token(time_token)?;
    let tz = resolve_zone(zone)?;

    let (year_i16, month_i8, day_i8) =
        check_date_components(date.year(), date.month(), date.day())?;
    let (hour_i8, minute_i8, second_i8) =
        check_time_components(time.hour(), time.minute(), time.second())?;

    let jiff_dt = JiffDate::new(year_i16, month_i8, day_i8)
        .map_err(|err| CalendarError::Overflow {
            message: err.to_string(),
        })?
        .at(hour_i8, minute_i8, second_i8, 0);

    let zoned = jiff_dt
        .to_zoned(tz)
        .map_err(|err| CalendarError::DisambiguationFailed {
            message: err.to_string(),
        })?;

    let epoch_ms = zoned.timestamp().as_millisecond();
    if epoch_ms <= 0 {
        return Err(CalendarError::InvalidEpochMs(epoch_ms));
    }
    Ok(epoch_ms)
}

/// Computes the journal filename and time token from a single epoch millisecond instant.
///
/// Avoids midnight-crossing skew by evaluating date and time from the identical instant.
///
/// # Errors
///
/// Returns [`CalendarError`] if `epoch_ms` is non-positive or timezone is unknown.
pub fn journal_stamp(
    epoch_ms: i64,
    zone: &str,
    format: DateFormat,
) -> Result<JournalStamp, CalendarError> {
    if epoch_ms <= 0 {
        return Err(CalendarError::InvalidEpochMs(epoch_ms));
    }
    let tz = resolve_zone(zone)?;
    let ts =
        jiff::Timestamp::from_millisecond(epoch_ms).map_err(|err| CalendarError::Overflow {
            message: err.to_string(),
        })?;
    let zoned = ts.to_zoned(tz);

    let date = CivilDate::from_jiff(zoned.date())?;
    let time = zoned.time();

    let filename = format_filename(date, format);
    let time_token = format!(
        "{:02}:{:02}:{:02}",
        time.hour(),
        time.minute(),
        time.second()
    );

    Ok(JournalStamp {
        filename,
        time_token,
    })
}

/// Converts a positive epoch millisecond to a local civil date in the given timezone.
///
/// # Errors
///
/// Returns [`CalendarError`] if `epoch_ms` is non-positive or timezone is unknown.
pub fn local_date(epoch_ms: i64, zone: &str) -> Result<CivilDate, CalendarError> {
    if epoch_ms <= 0 {
        return Err(CalendarError::InvalidEpochMs(epoch_ms));
    }
    let tz = resolve_zone(zone)?;
    let ts =
        jiff::Timestamp::from_millisecond(epoch_ms).map_err(|err| CalendarError::Overflow {
            message: err.to_string(),
        })?;
    let zoned = ts.to_zoned(tz);
    CivilDate::from_jiff(zoned.date())
}

/// Calculates the half-open interval `[start_ms, end_ms)` for a local calendar day.
///
/// The end bound is the start of the next local day, correctly handling 23-hour and
/// 25-hour daylight saving time transition days.
///
/// # Errors
///
/// Returns [`CalendarError`] if the timezone is unknown or date arithmetic fails.
pub fn day_bounds(date: CivilDate, zone: &str) -> Result<(i64, i64), CalendarError> {
    let timezone = resolve_zone(zone)?;
    let date = date.to_jiff()?;
    let next = date.tomorrow().map_err(|error| CalendarError::Overflow {
        message: error.to_string(),
    })?;
    Ok((
        first_instant_of_day(date, &timezone)?,
        first_instant_of_day(next, &timezone)?,
    ))
}

fn first_instant_of_day(date: JiffDate, timezone: &TimeZone) -> Result<i64, CalendarError> {
    let midnight = timezone.to_ambiguous_timestamp(date.at(0, 0, 0, 0));
    let timestamp = match midnight.offset() {
        jiff::tz::AmbiguousOffset::Gap { .. } => {
            let before_gap =
                midnight
                    .earlier()
                    .map_err(|error| CalendarError::DisambiguationFailed {
                        message: error.to_string(),
                    })?;
            timezone
                .following(before_gap)
                .next()
                .ok_or_else(|| CalendarError::DisambiguationFailed {
                    message: "missing transition for a midnight gap".to_owned(),
                })?
                .timestamp()
        }
        jiff::tz::AmbiguousOffset::Unambiguous { offset: _ }
        | jiff::tz::AmbiguousOffset::Fold { .. } => {
            midnight
                .compatible()
                .map_err(|error| CalendarError::DisambiguationFailed {
                    message: error.to_string(),
                })?
        }
    };
    Ok(timestamp.as_millisecond())
}
