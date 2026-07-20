//! Lomo/Thino time-header recognition without using line strings as write-back authority.

use lomo_core::LomoError;

use crate::limits::validation;

const UTF8_BOM: char = '\u{FEFF}';
const ZERO_WIDTH_SPACE: char = '\u{200B}';

/// Parsed Lomo/Thino memo header: `- HH:mm[:ss]` plus optional same-line content.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ParsedMemoHeader {
    time_part: String,
    content_part: String,
}

impl ParsedMemoHeader {
    #[must_use]
    pub fn time_part(&self) -> &str {
        &self.time_part
    }

    #[must_use]
    pub fn content_part(&self) -> &str {
        &self.content_part
    }
}

/// Parses a single logical line as a memo time header.
///
/// Accepts optional leading BOM / zero-width / whitespace, a `-` marker, a supported time token,
/// and optional same-line content separated by ignorable whitespace.
#[must_use]
pub fn parse_memo_header_line(line: &str) -> Option<ParsedMemoHeader> {
    let after_bom_ws = trim_start_ignorable(line);
    let after_dash = after_bom_ws.strip_prefix('-')?;
    let after_dash = trim_start_ignorable(after_dash);
    if after_dash.is_empty() {
        return None;
    }
    let time_end = match_supported_time(after_dash)?;
    let boundary_ok = time_end == after_dash.len()
        || after_dash[time_end..]
            .chars()
            .next()
            .is_some_and(is_ignorable_header_separator);
    if !boundary_ok {
        return None;
    }
    let time_part = after_dash[..time_end].to_owned();
    let content_part = trim_start_ignorable(&after_dash[time_end..]).to_owned();
    Some(ParsedMemoHeader {
        time_part,
        content_part,
    })
}

/// Validates a filename stem used as `dateKey` / plain identity prefix.
///
/// # Errors
///
/// Returns validation when the stem is empty, contains `_`, or contains control characters.
pub fn validate_filename_stem(filename_stem: &str) -> Result<(), LomoError> {
    if filename_stem.is_empty()
        || filename_stem.contains('_')
        || filename_stem.chars().any(char::is_control)
    {
        return Err(validation(
            "invalid_filename_stem",
            "filename stem must be non-empty and free of '_' / controls",
        ));
    }
    Ok(())
}

fn trim_start_ignorable(input: &str) -> &str {
    let mut end = 0usize;
    for (index, ch) in input.char_indices() {
        if is_ignorable_header_separator(ch) {
            end = index + ch.len_utf8();
        } else {
            break;
        }
    }
    &input[end..]
}

const fn is_ignorable_header_separator(ch: char) -> bool {
    ch.is_whitespace() || ch == UTF8_BOM || ch == ZERO_WIDTH_SPACE
}

/// Matches `H:mm`, `HH:mm`, `H:mm:ss`, or `HH:mm:ss` at the start of `input`.
///
/// Returns the byte end index of the matched time token.
fn match_supported_time(input: &str) -> Option<usize> {
    let bytes = input.as_bytes();
    let mut index = 0usize;
    let hour_start = index;
    while index < bytes.len() && bytes[index].is_ascii_digit() {
        index += 1;
    }
    let hour_len = index - hour_start;
    if !(1..=2).contains(&hour_len) {
        return None;
    }
    if bytes.get(index) != Some(&b':') {
        return None;
    }
    index += 1;
    let minute_start = index;
    while index < bytes.len() && bytes[index].is_ascii_digit() {
        index += 1;
    }
    if index - minute_start != 2 {
        return None;
    }
    let hour = parse_two_digit_component(&input[hour_start..hour_start + hour_len])?;
    let minute = parse_two_digit_component(&input[minute_start..minute_start + 2])?;
    if hour > 23 || minute > 59 {
        return None;
    }
    if bytes.get(index) == Some(&b':') {
        index += 1;
        let second_start = index;
        while index < bytes.len() && bytes[index].is_ascii_digit() {
            index += 1;
        }
        if index - second_start != 2 {
            return None;
        }
        let second = parse_two_digit_component(&input[second_start..second_start + 2])?;
        if second > 59 {
            return None;
        }
    }
    Some(index)
}

fn parse_two_digit_component(raw: &str) -> Option<u8> {
    let mut value = 0u8;
    for byte in raw.bytes() {
        if !byte.is_ascii_digit() {
            return None;
        }
        value = value
            .checked_mul(10)?
            .checked_add(byte.saturating_sub(b'0'))?;
    }
    Some(value)
}
