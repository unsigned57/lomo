//! Owner-side conflict text merge and resolution suggestion (T33 / B02 item 5).
//!
//! Kotlin may display the suggestion and submit the user's choice or edited merge text; the merge
//! algorithm, identity-keyed memo merge delegation, and time adjudication live here. The memo
//! identity merge delegates to [`lomo_workspace::merge_memo_shard_by_identity`]; owner decline
//! (not Lomo/Thino shards, no shared identity) falls back to the non-identity text merge, and
//! owner validation failure declines the identity branch the same way the FFI adapter did.
//!
//! Merge policy: bounded LCS-anchored merge; disjoint memo content concatenates older side first;
//! anything uncertain returns `None` so the conflict stays open for review.
//!
//! Index-free implementation: the workspace denies `indexing_slicing`/`unwrap_used`, so the DP
//! table is filled and walked through `split_at_mut`/`windows`/`iter_mut().rev()` zips, and every
//! fallible lookup short-circuits instead of fabricating a boundary value.

use lomo_workspace::merge_memo_shard_by_identity;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

/// Maximum lines per side accepted for the anchored merge (fail closed beyond this).
const MAX_MERGE_LINE_COUNT: usize = 1_000;
/// Maximum LCS comparison cells (`(local+1) * (remote+1)`) accepted (fail closed beyond this).
const MAX_MERGE_COMPARISON_CELLS: u64 = 250_000;
/// Timestamp gap at which the newer side alone is suggested without a merge.
const MUCH_NEWER_THRESHOLD_MS: i64 = 5 * 60 * 1_000;

/// Side the suggestion resolves toward. `KeepRemote` maps to `KEEP_INCOMING` for inbox review.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ConflictSuggestionChoice {
    KeepLocal,
    KeepRemote,
    MergeText,
}

/// Owner-computed conflict suggestion for one path.
///
/// - `safe_choice`: only Some when resolution requires no timestamp guesswork.
/// - `suggested_choice`: `safe_choice` else the newer-side choice when one side is ≥ threshold newer.
/// - `merged_body`: the merged text whenever the merge succeeded (display/preview + `MERGE_TEXT`
///   write-back); not implied by either choice field.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ConflictSuggestion {
    pub safe_choice: Option<ConflictSuggestionChoice>,
    pub suggested_choice: Option<ConflictSuggestionChoice>,
    pub merged_body: Option<String>,
}

/// Computes the conflict suggestion for one text path.
///
/// Binary paths and inputs that cannot merge deterministically yield empty choices; the caller
/// keeps the conflict open for user resolution.
#[must_use]
pub fn suggest_conflict_resolution(
    local_text: Option<&str>,
    remote_text: Option<&str>,
    local_last_modified_ms: Option<i64>,
    remote_last_modified_ms: Option<i64>,
    is_binary: bool,
) -> ConflictSuggestion {
    if is_binary {
        return ConflictSuggestion {
            safe_choice: None,
            suggested_choice: None,
            merged_body: None,
        };
    }
    let merged_body = merge_conflict_text(
        local_text,
        remote_text,
        local_last_modified_ms,
        remote_last_modified_ms,
    );
    let normalized_local = kt_trim(local_text.unwrap_or(""));
    let normalized_remote = kt_trim(remote_text.unwrap_or(""));
    let newer = newer_side_choice(local_last_modified_ms, remote_last_modified_ms);
    let merge_choice = merged_body
        .as_deref()
        .filter(|merged| {
            let normalized = kt_trim(merged);
            normalized != normalized_local && normalized != normalized_remote
        })
        .map(|_| ConflictSuggestionChoice::MergeText);

    let safe_choice = if normalized_local == normalized_remote {
        Some(newer.unwrap_or(ConflictSuggestionChoice::KeepRemote))
    } else if kt_is_blank(normalized_local) {
        Some(ConflictSuggestionChoice::KeepRemote)
    } else if kt_is_blank(normalized_remote) {
        Some(ConflictSuggestionChoice::KeepLocal)
    } else if normalized_remote.contains(normalized_local) {
        Some(ConflictSuggestionChoice::KeepRemote)
    } else if normalized_local.contains(normalized_remote) {
        Some(ConflictSuggestionChoice::KeepLocal)
    } else {
        merge_choice
    };
    let suggested_choice = safe_choice.or(newer);
    ConflictSuggestion {
        safe_choice,
        suggested_choice,
        merged_body,
    }
}

const fn newer_side_choice(
    local_last_modified_ms: Option<i64>,
    remote_last_modified_ms: Option<i64>,
) -> Option<ConflictSuggestionChoice> {
    let (Some(local), Some(remote)) = (local_last_modified_ms, remote_last_modified_ms) else {
        return None;
    };
    if local - remote >= MUCH_NEWER_THRESHOLD_MS {
        Some(ConflictSuggestionChoice::KeepLocal)
    } else if remote - local >= MUCH_NEWER_THRESHOLD_MS {
        Some(ConflictSuggestionChoice::KeepRemote)
    } else {
        None
    }
}

/// Bounded deterministic text merge. Returns `None` when the merge is uncertain.
fn merge_conflict_text(
    local_text: Option<&str>,
    remote_text: Option<&str>,
    local_last_modified_ms: Option<i64>,
    remote_last_modified_ms: Option<i64>,
) -> Option<String> {
    let local_empty = local_text.is_none_or(str::is_empty);
    let remote_empty = remote_text.is_none_or(str::is_empty);
    if local_empty {
        return remote_text.map(str::to_owned);
    }
    if remote_empty {
        return local_text.map(str::to_owned);
    }
    let (Some(local_text), Some(remote_text)) = (local_text, remote_text) else {
        return None;
    };
    if local_text == remote_text {
        return Some(local_text.to_owned());
    }
    merge_non_empty_distinct(
        local_text,
        remote_text,
        local_last_modified_ms,
        remote_last_modified_ms,
    )
}

fn merge_non_empty_distinct(
    local_text: &str,
    remote_text: &str,
    local_last_modified_ms: Option<i64>,
    remote_last_modified_ms: Option<i64>,
) -> Option<String> {
    let local_lines = merge_lines(local_text);
    let remote_lines = merge_lines(remote_text);
    let cells = (local_lines.len() as u64 + 1).saturating_mul(remote_lines.len() as u64 + 1);
    if local_lines.len() > MAX_MERGE_LINE_COUNT
        || remote_lines.len() > MAX_MERGE_LINE_COUNT
        || cells > MAX_MERGE_COMPARISON_CELLS
    {
        return None;
    }

    let anchors: Vec<(usize, usize)> = compute_anchors(&local_lines, &remote_lines)
        .into_iter()
        .filter(|(local_anchor, _)| {
            local_lines
                .get(*local_anchor)
                .is_some_and(|line| kt_is_not_blank(line))
        })
        .collect();
    let anchored = merge_anchored_segments(&local_lines, &remote_lines, &anchors)?;

    let tail = merge_segment(
        &skip_to_vec(&local_lines, anchored.local_cursor),
        &skip_to_vec(&remote_lines, anchored.remote_cursor),
    );
    if let Some(tail) = tail {
        let mut lines = anchored.lines;
        lines.extend(tail);
        return Some(lines.join("\n"));
    }
    if !anchors.is_empty() {
        return None;
    }
    merge_disjoint_memo_content(
        local_text,
        remote_text,
        &local_lines,
        &remote_lines,
        local_last_modified_ms,
        remote_last_modified_ms,
    )
}

fn skip_to_vec<'a>(lines: &'a [&'a str], from: usize) -> Vec<&'a str> {
    lines.iter().skip(from).copied().collect()
}

struct AnchoredMerge {
    lines: Vec<String>,
    local_cursor: usize,
    remote_cursor: usize,
}

fn merge_anchored_segments(
    local_lines: &[&str],
    remote_lines: &[&str],
    anchors: &[(usize, usize)],
) -> Option<AnchoredMerge> {
    let mut merged_lines = Vec::new();
    let mut local_cursor = 0;
    let mut remote_cursor = 0;

    for &(local_anchor, remote_anchor) in anchors {
        let local_segment = skip_take_vec(
            local_lines,
            local_cursor,
            local_anchor.saturating_sub(local_cursor),
        );
        let remote_segment = skip_take_vec(
            remote_lines,
            remote_cursor,
            remote_anchor.saturating_sub(remote_cursor),
        );
        merged_lines.extend(merge_segment(&local_segment, &remote_segment)?);
        let anchor_line = local_lines.get(local_anchor)?;
        merged_lines.push((*anchor_line).to_owned());
        local_cursor = local_anchor + 1;
        remote_cursor = remote_anchor + 1;
    }

    Some(AnchoredMerge {
        lines: merged_lines,
        local_cursor,
        remote_cursor,
    })
}

fn skip_take_vec<'a>(lines: &'a [&'a str], from: usize, count: usize) -> Vec<&'a str> {
    lines.iter().skip(from).take(count).copied().collect()
}

/// LCS anchor walk: matched pairs in scan order (greedy backtrack prefers skipping local lines).
fn compute_anchors(local_lines: &[&str], remote_lines: &[&str]) -> Vec<(usize, usize)> {
    let local_size = local_lines.len();
    let remote_size = remote_lines.len();
    let mut table = vec![vec![0u32; remote_size + 1]; local_size + 1];

    // Fill bottom-up: `row` is `table[i]`, `next_row` is `table[i + 1]`; cells fill right-to-left
    // so `right` carries the already-computed `row[j + 1]` value.
    for (local_index, local_line) in (0..local_size).rev().zip(local_lines.iter().rev()) {
        let (head, tail) = table.split_at_mut(local_index + 1);
        let (Some(row), Some(next_row)) = (head.last_mut(), tail.first()) else {
            continue;
        };
        let mut right = 0u32;
        // Alignment: remote_lines.rev() ↔ next_row.windows(2).rev() ↔ row cells j<remote_size rev.
        for ((remote_line, window), cell) in remote_lines
            .iter()
            .rev()
            .zip(next_row.windows(2).rev())
            .zip(row.iter_mut().rev().skip(1))
        {
            let (Some(&down), Some(&diag)) = (window.first(), window.last()) else {
                continue;
            };
            *cell = if local_line == remote_line {
                diag + 1
            } else {
                down.max(right)
            };
            right = *cell;
        }
    }

    let mut anchors = Vec::new();
    let mut local_index = 0;
    let mut remote_index = 0;
    while local_index < local_size && remote_index < remote_size {
        let (Some(&local_line), Some(&remote_line)) =
            (local_lines.get(local_index), remote_lines.get(remote_index))
        else {
            break;
        };
        if local_line == remote_line {
            anchors.push((local_index, remote_index));
            local_index += 1;
            remote_index += 1;
            continue;
        }
        let (Some(&down), Some(&right)) = (
            table
                .get(local_index + 1)
                .and_then(|row| row.get(remote_index)),
            table
                .get(local_index)
                .and_then(|row| row.get(remote_index + 1)),
        ) else {
            break;
        };
        if down >= right {
            local_index += 1;
        } else {
            remote_index += 1;
        }
    }
    anchors
}

/// Merges one between-anchor segment: equal sides, one empty side, or strict subsequence.
fn merge_segment(local_lines: &[&str], remote_lines: &[&str]) -> Option<Vec<String>> {
    if local_lines == remote_lines {
        Some(to_owned_vec(local_lines))
    } else if local_lines.is_empty() {
        Some(to_owned_vec(remote_lines))
    } else if remote_lines.is_empty() {
        Some(to_owned_vec(local_lines))
    } else if is_subsequence(local_lines, remote_lines) {
        Some(to_owned_vec(remote_lines))
    } else if is_subsequence(remote_lines, local_lines) {
        Some(to_owned_vec(local_lines))
    } else {
        None
    }
}

fn to_owned_vec(lines: &[&str]) -> Vec<String> {
    lines.iter().map(|line| (*line).to_owned()).collect()
}

fn is_subsequence(smaller: &[&str], larger: &[&str]) -> bool {
    let mut larger_iter = larger.iter();
    smaller
        .iter()
        .all(|needle| larger_iter.any(|line| line == needle))
}

/// Disjoint memo content: owner identity merge first, then older-side-first concatenation.
fn merge_disjoint_memo_content(
    local_text: &str,
    remote_text: &str,
    local_lines: &[&str],
    remote_lines: &[&str],
    local_last_modified_ms: Option<i64>,
    remote_last_modified_ms: Option<i64>,
) -> Option<String> {
    if !looks_like_memo_content(local_lines) || !looks_like_memo_content(remote_lines) {
        return None;
    }
    let local_meaningful = meaningful_line_set(local_lines);
    let remote_meaningful = meaningful_line_set(remote_lines);
    if local_meaningful
        .iter()
        .any(|line| remote_meaningful.contains(line))
    {
        return None;
    }

    // Shared-identity memo merge is owner-only; decline or owner validation failure falls through
    // to disjoint concat exactly as the Kotlin FFI adapter did.
    if let Ok(Some(merged)) = merge_memo_shard_by_identity(
        local_text,
        remote_text,
        local_last_modified_ms,
        remote_last_modified_ms,
    ) {
        return Some(merged);
    }

    let (older, newer) =
        if remote_should_come_first(local_last_modified_ms, remote_last_modified_ms) {
            (remote_text, local_text)
        } else {
            (local_text, remote_text)
        };
    Some(format!(
        "{}\n\n{}",
        older.trim_end_matches('\n'),
        newer.trim_start_matches('\n'),
    ))
}

fn looks_like_memo_content(lines: &[&str]) -> bool {
    lines.iter().any(|line| kt_is_not_blank(line))
}

fn meaningful_line_set(lines: &[&str]) -> HashSet<String> {
    lines
        .iter()
        .map(|line| kt_trim(line).to_owned())
        .filter(|line| kt_is_not_blank(line))
        .collect()
}

const fn remote_should_come_first(
    local_last_modified_ms: Option<i64>,
    remote_last_modified_ms: Option<i64>,
) -> bool {
    matches!(
        (local_last_modified_ms, remote_last_modified_ms),
        (Some(local), Some(remote)) if remote < local
    )
}

fn merge_lines(text: &str) -> Vec<&str> {
    if text.is_empty() {
        Vec::new()
    } else {
        text.split('\n').collect()
    }
}

/// Kotlin `String.trim()`: strips chars with code ≤ space.
fn kt_trim(text: &str) -> &str {
    text.trim_matches(|c: char| c <= ' ')
}

/// Kotlin `Char.isWhitespace` (Java): space, \t-\r, file/group/record/unit separators, Unicode Zs.
fn is_java_whitespace(c: char) -> bool {
    c.is_whitespace() || ('\u{1c}'..='\u{1f}').contains(&c)
}

fn kt_is_blank(text: &str) -> bool {
    text.chars().all(is_java_whitespace)
}

fn kt_is_not_blank(text: &str) -> bool {
    !kt_is_blank(text)
}
