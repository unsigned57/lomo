//! Shared tag-body classification for storage analysis and render IR.
//!
//! One character law and one text scanner feed both projections so dual tag
//! authorities cannot drift.

/// Returns whether `ch` may appear in a Lomo tag body (Kotlin characterization parity).
#[must_use]
pub fn is_tag_body_char(ch: char) -> bool {
    // Kotlin: [\p{L}\p{N}\p{So}\p{Sc}_][\p{L}\p{N}\p{So}\p{Sc}_/]*
    ch == '_' || ch == '/' || ch.is_alphanumeric() || is_other_symbol_or_currency(ch)
}

/// Yields `(name, absolute_start, absolute_end)` for each tag match in `content`.
///
/// `absolute_*` offsets are relative to `content` (not a parent buffer).
pub fn iter_tag_matches(content: &str) -> Vec<(String, usize, usize)> {
    let mut matches = Vec::new();
    let bytes = content.as_bytes();
    let mut index = 0usize;
    while index < bytes.len() {
        let at_start = index == 0;
        let prev_is_ws = index > 0 && is_ascii_whitespace(bytes[index - 1]);
        if bytes[index] == b'#' && (at_start || prev_is_ws) {
            let value_start = index + 1;
            let mut value_end = value_start;
            while value_end < bytes.len() {
                let Some(ch) = content[value_end..].chars().next() else {
                    break;
                };
                if is_tag_body_char(ch) {
                    value_end += ch.len_utf8();
                } else {
                    break;
                }
            }
            if value_end > value_start {
                let boundary_ok = value_end == bytes.len()
                    || matches!(bytes[value_end], b' ' | b'\t' | b'\n' | b'\r' | b',');
                if boundary_ok {
                    let mut tag = content[value_start..value_end].to_owned();
                    while tag.ends_with('/') {
                        tag.pop();
                    }
                    if !tag.is_empty() {
                        matches.push((tag, index, value_end));
                        index = value_end;
                        continue;
                    }
                }
            }
        }
        index += 1;
    }
    matches
}

const fn is_ascii_whitespace(byte: u8) -> bool {
    matches!(byte, b' ' | b'\t' | b'\n' | b'\r')
}

fn is_other_symbol_or_currency(ch: char) -> bool {
    if ch.is_ascii() || ch.is_control() || ch.is_whitespace() {
        return false;
    }
    // Reject common CJK punctuation separators that should end tags.
    !matches!(
        ch,
        '，' | '。' | '、' | '；' | '：' | '！' | '？' | '（' | '）' | '【' | '】' | '《' | '》'
    )
}
