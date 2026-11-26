//! Search highlighting and excerpt presentation sharing `lomo-store` query parsing rules.

use std::collections::{BTreeMap, HashSet};
use std::ops::Range;

use lomo_core::LomoError;
use lomo_store::{
    SearchQueryPlan, SearchSpanPlan, analyze_charabia_tokens, analyze_cjk_bigrams,
    is_bounded_subsequence_token, is_cjk, is_emoji_char, is_pinyin_query_token, normalize_cjk_char,
    source_path_raw_spans,
};
use pinyin::ToPinyinMulti;

use crate::error::validation;
use crate::search::SearchMode;

const MAX_PINYIN_COMBOS_PER_UNIT: usize = 16;
const MAX_CHAR_READINGS: usize = 4;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MatchSource {
    Body,
    Path,
    Pinyin,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SearchExcerpt {
    pub text: String,
    pub highlights: Vec<Range<usize>>,
    pub source: MatchSource,
    pub body_start: Option<usize>,
}

#[derive(Default)]
struct ExcerptTerms {
    charabia_words: Vec<String>,
    cjk_fallback_terms: Vec<String>,
    raw_paths: Vec<String>,
    fuzzy_words: Vec<String>,
    pinyin_tokens: Vec<String>,
}

/// Builds one source-verified excerpt and UTF-8 highlight spans for `mode` (`Fulltext` or `Fuzzy`).
///
/// Degrades to an unhighlighted body excerpt when a legitimate match (e.g., tag-only or filter-only
/// fallback) has no direct body, pinyin, or path span.
///
/// # Errors
///
/// Returns `query_too_long` on oversized queries or `invalid_search_evidence` if excerpt slicing
/// violates UTF-8 boundaries.
pub fn search_excerpt(
    mode: SearchMode,
    path: &str,
    body: &str,
    query: &str,
) -> Result<SearchExcerpt, LomoError> {
    let plan = SearchQueryPlan::parse(Some(query))?;
    if plan.is_empty() {
        return make_excerpt(body, &[], MatchSource::Body);
    }

    let terms = ExcerptTerms::from_plan(&plan);
    let fulltext_body = direct_token_and_literal_ranges(body, &terms, false);
    if !fulltext_body.is_empty() {
        return make_excerpt(body, &fulltext_body, MatchSource::Body);
    }

    if mode == SearchMode::Fuzzy {
        let fuzzy_body = fuzzy_token_ranges(body, &terms, false);
        if !fuzzy_body.is_empty() {
            return make_excerpt(body, &fuzzy_body, MatchSource::Body);
        }
        if let Some(pinyin_ranges) = best_pinyin_ranges(body, &terms) {
            return make_excerpt(body, &pinyin_ranges, MatchSource::Pinyin);
        }
    }

    let fulltext_path = direct_token_and_literal_ranges(path, &terms, true);
    if !fulltext_path.is_empty() {
        return make_excerpt(path, &fulltext_path, MatchSource::Path);
    }

    if mode == SearchMode::Fuzzy {
        let fuzzy_path = fuzzy_token_ranges(path, &terms, true);
        if !fuzzy_path.is_empty() {
            return make_excerpt(path, &fuzzy_path, MatchSource::Path);
        }
    }

    make_excerpt(body, &[], MatchSource::Body)
}

impl ExcerptTerms {
    fn from_plan(plan: &SearchQueryPlan) -> Self {
        let mut collector = TermCollector::default();
        for span in plan.chunks.iter().flat_map(|chunk| chunk.spans.iter()) {
            collector.collect_span(span);
        }
        collector.terms
    }
}

#[derive(Default)]
struct TermCollector {
    terms: ExcerptTerms,
    seen_charabia: HashSet<String>,
    seen_cjk: HashSet<String>,
    seen_paths: HashSet<String>,
    seen_fuzzy: HashSet<String>,
    seen_pinyin: HashSet<String>,
}

impl TermCollector {
    fn collect_span(&mut self, span: &SearchSpanPlan) {
        match span {
            SearchSpanPlan::CjkRun {
                charabia_tokens,
                bigrams,
                unigrams,
            } => {
                for token in charabia_tokens {
                    push_dedup(
                        &mut self.seen_charabia,
                        &mut self.terms.charabia_words,
                        token,
                    );
                }
                let fallback = if bigrams.is_empty() {
                    unigrams.first().map_or(&[][..], std::slice::from_ref)
                } else {
                    bigrams.as_slice()
                };
                for term in fallback {
                    push_dedup(&mut self.seen_cjk, &mut self.terms.cjk_fallback_terms, term);
                }
            }
            SearchSpanPlan::NonCjk { tokens, raw_path } => {
                for token in tokens {
                    push_dedup(
                        &mut self.seen_charabia,
                        &mut self.terms.charabia_words,
                        token,
                    );
                    if !token.chars().all(is_emoji_char) && !token.chars().any(is_cjk) {
                        push_dedup(&mut self.seen_fuzzy, &mut self.terms.fuzzy_words, token);
                    }
                    if is_pinyin_query_token(token) {
                        push_dedup(&mut self.seen_pinyin, &mut self.terms.pinyin_tokens, token);
                    }
                }
                if let Some(path_token) = raw_path {
                    push_dedup(&mut self.seen_paths, &mut self.terms.raw_paths, path_token);
                }
            }
        }
    }
}

fn push_dedup(seen: &mut HashSet<String>, out: &mut Vec<String>, value: &str) {
    if !value.is_empty() && seen.insert(value.to_owned()) {
        out.push(value.to_owned());
    }
}

fn direct_token_and_literal_ranges(
    text: &str,
    query: &ExcerptTerms,
    include_raw_path_spans: bool,
) -> Vec<Range<usize>> {
    let mut ranges = Vec::new();
    for doc_token in analyze_charabia_tokens(text) {
        if query
            .charabia_words
            .iter()
            .any(|needle| token_direct_match(&doc_token.text, needle))
        {
            ranges.push(doc_token.byte_start..doc_token.byte_end);
        }
    }
    for doc_bigram in analyze_cjk_bigrams(text) {
        if query.cjk_fallback_terms.contains(&doc_bigram.text) {
            ranges.push(doc_bigram.byte_start..doc_bigram.byte_end);
        }
    }
    ranges.extend(literal_ranges(text, &query.raw_paths));
    if include_raw_path_spans {
        for (raw_tok, range) in source_path_raw_spans(text) {
            if query
                .raw_paths
                .iter()
                .chain(query.charabia_words.iter())
                .any(|needle| token_direct_match(&raw_tok, needle))
            {
                ranges.push(range);
            }
        }
    }
    normalize_ranges(ranges)
}

fn token_direct_match(candidate: &str, needle: &str) -> bool {
    if needle.is_empty() {
        return false;
    }
    if needle.chars().all(is_emoji_char) {
        candidate == needle
    } else {
        candidate == needle || candidate.starts_with(needle)
    }
}

fn fuzzy_token_ranges(
    text: &str,
    query: &ExcerptTerms,
    include_raw_path_spans: bool,
) -> Vec<Range<usize>> {
    let mut candidates: Vec<(String, Range<usize>)> = analyze_charabia_tokens(text)
        .into_iter()
        .filter(|tok| !tok.text.chars().all(is_emoji_char) && !tok.text.chars().any(is_cjk))
        .map(|tok| (tok.text, tok.byte_start..tok.byte_end))
        .collect();
    if include_raw_path_spans {
        candidates.extend(source_path_raw_spans(text));
    } else {
        for (start, slice) in whitespace_spans(text) {
            let lower = slice.to_ascii_lowercase();
            if lower.contains(['_', '-', '.', '/']) {
                candidates.push((lower, start..start + slice.len()));
            }
        }
    }

    let needles: Vec<&str> = query
        .fuzzy_words
        .iter()
        .chain(query.raw_paths.iter())
        .map(String::as_str)
        .collect();

    let mut ranges = Vec::new();
    for (doc_text, range) in candidates {
        for needle in &needles {
            if fuzzy_term_matches(&doc_text, needle) {
                ranges.push(range.clone());
            }
        }
    }
    normalize_ranges(ranges)
}

fn fuzzy_term_matches(candidate: &str, needle: &str) -> bool {
    let needle_chars: Vec<char> = needle.chars().collect();
    let cand_chars: Vec<char> = candidate.chars().collect();
    if needle_chars.is_empty() || cand_chars.is_empty() {
        return false;
    }
    if is_bounded_subsequence_token(needle) && is_bounded_subsequence(&cand_chars, &needle_chars) {
        return true;
    }
    if needle_chars.len() >= 3 {
        let max_dist = if needle_chars.len() >= 6 { 2 } else { 1 };
        if cand_chars.len().abs_diff(needle_chars.len()) <= max_dist
            && bounded_damerau_levenshtein(&cand_chars, &needle_chars, max_dist).is_some()
        {
            return true;
        }
        if needle_chars.len() >= 4 {
            let min_prefix = needle_chars.len().saturating_sub(1);
            let max_prefix = (needle_chars.len() + 1).min(cand_chars.len());
            for prefix_len in min_prefix..=max_prefix {
                if let Some(prefix) = cand_chars.get(..prefix_len)
                    && bounded_damerau_levenshtein(prefix, &needle_chars, 1).is_some()
                {
                    return true;
                }
            }
        }
    }
    false
}

fn best_pinyin_ranges(text: &str, query: &ExcerptTerms) -> Option<Vec<Range<usize>>> {
    let mut collected = Vec::new();
    for token in &query.pinyin_tokens {
        let chars: Vec<char> = token.chars().collect();
        if let Some(ranges) = pinyin_evidence(text, &chars) {
            collected.extend(ranges);
        } else if chars.len() >= 4
            && let Some(range) = fuzzy_pinyin_cjk_run(text, &chars)
        {
            collected.push(range);
        }
    }
    let normalized = normalize_ranges(collected);
    (!normalized.is_empty()).then_some(normalized)
}

fn fuzzy_pinyin_cjk_run(text: &str, needle: &[char]) -> Option<Range<usize>> {
    let max_dist = if needle.len() >= 6 { 2 } else { 1 };
    let mut current: Vec<(char, Range<usize>)> = Vec::new();

    for (byte, ch) in text.char_indices() {
        if is_cjk(ch) {
            current.push((normalize_cjk_char(ch), byte..byte + ch.len_utf8()));
        } else if !current.is_empty() {
            if let Some(matched) = match_cjk_run_fuzzy_pinyin(&current, needle, max_dist) {
                return Some(matched);
            }
            current.clear();
        }
    }
    if !current.is_empty()
        && let Some(matched) = match_cjk_run_fuzzy_pinyin(&current, needle, max_dist)
    {
        return Some(matched);
    }
    None
}

fn match_cjk_run_fuzzy_pinyin(
    run: &[(char, Range<usize>)],
    needle: &[char],
    max_dist: usize,
) -> Option<Range<usize>> {
    let mut readings_per_char = Vec::with_capacity(run.len());
    for (ch, _) in run {
        let multi = ch.to_pinyin_multi()?;
        let mut readings = Vec::new();
        for item in multi.into_iter().take(MAX_CHAR_READINGS) {
            let plain = item.plain();
            if !plain.is_empty() && !readings.contains(&plain) {
                readings.push(plain);
            }
        }
        if readings.is_empty() {
            return None;
        }
        readings_per_char.push(readings);
    }
    for window_len in 2..=run.len().min(8) {
        for (start, window) in readings_per_char.windows(window_len).enumerate() {
            for combo in expand_pinyin_combos(window, MAX_PINYIN_COMBOS_PER_UNIT) {
                let joined: Vec<char> = combo.chars().collect();
                if joined.len().abs_diff(needle.len()) <= max_dist
                    && bounded_damerau_levenshtein(&joined, needle, max_dist).is_some()
                    && let (Some((_, first)), Some((_, last))) =
                        (run.get(start), run.get(start + window_len - 1))
                {
                    return Some(first.start..last.end);
                }
            }
        }
    }
    None
}

fn expand_pinyin_combos(per_char: &[Vec<&'static str>], max_combos: usize) -> Vec<String> {
    let mut acc = vec![String::new()];
    for choices in per_char {
        let mut next = Vec::with_capacity(acc.len().saturating_mul(choices.len()).min(max_combos));
        for prefix in &acc {
            for choice in choices {
                if next.len() >= max_combos {
                    break;
                }
                let mut combined = String::with_capacity(prefix.len() + choice.len());
                combined.push_str(prefix);
                combined.push_str(choice);
                next.push(combined);
            }
            if next.len() >= max_combos {
                break;
            }
        }
        acc = next;
    }
    acc
}

fn bounded_damerau_levenshtein(left: &[char], right: &[char], max_dist: usize) -> Option<usize> {
    let n = left.len();
    let m = right.len();
    if n.abs_diff(m) > max_dist {
        return None;
    }
    let sentinel = max_dist + 1;
    let mut prev2 = vec![sentinel; m + 1];
    let mut prev1: Vec<usize> = (0..=m).map(|j| j.min(sentinel)).collect();
    let mut curr = vec![sentinel; m + 1];
    let mut prev1_min = 0usize;

    for i in 1..=n {
        curr.fill(sentinel);
        let row_base = i.min(sentinel);
        if let Some(first) = curr.first_mut() {
            *first = row_base;
        }
        let mut curr_min = row_base;
        let Some(&a) = left.get(i - 1) else {
            continue;
        };
        for j in 1..=m {
            let Some(&b) = right.get(j - 1) else {
                continue;
            };
            let cost = usize::from(a != b);
            let del = prev1.get(j).copied().unwrap_or(sentinel).saturating_add(1);
            let ins = curr
                .get(j - 1)
                .copied()
                .unwrap_or(sentinel)
                .saturating_add(1);
            let sub = prev1
                .get(j - 1)
                .copied()
                .unwrap_or(sentinel)
                .saturating_add(cost);
            let mut best = del.min(ins).min(sub);
            if i > 1 && j > 1 && left.get(i - 2) == Some(&b) && right.get(j - 2) == Some(&a) {
                let trans = prev2
                    .get(j - 2)
                    .copied()
                    .unwrap_or(sentinel)
                    .saturating_add(cost);
                best = best.min(trans);
            }
            if let Some(cell) = curr.get_mut(j) {
                *cell = best.min(sentinel);
            }
            curr_min = curr_min.min(best.min(sentinel));
        }
        if curr_min > max_dist && prev1_min > max_dist {
            return None;
        }
        prev1_min = curr_min;
        std::mem::swap(&mut prev2, &mut prev1);
        std::mem::swap(&mut prev1, &mut curr);
    }

    let final_dist = prev1.get(m).copied().unwrap_or(sentinel);
    (final_dist <= max_dist).then_some(final_dist)
}

fn is_bounded_subsequence(haystack: &[char], needle: &[char]) -> bool {
    let mut needle_idx = 0usize;
    for ch in haystack {
        if needle.get(needle_idx) == Some(ch) {
            needle_idx += 1;
            if needle_idx == needle.len() {
                return true;
            }
        }
    }
    false
}

fn whitespace_spans(text: &str) -> Vec<(usize, &str)> {
    let mut out = Vec::new();
    let mut start: Option<usize> = None;
    for (idx, ch) in text.char_indices() {
        if ch.is_whitespace() {
            if let Some(s) = start.take()
                && let Some(slice) = text.get(s..idx)
            {
                out.push((s, slice));
            }
        } else if start.is_none() {
            start = Some(idx);
        }
    }
    if let Some(s) = start
        && let Some(slice) = text.get(s..)
    {
        out.push((s, slice));
    }
    out
}

fn mapped_chars(text: &str) -> impl Iterator<Item = (char, Range<usize>)> + '_ {
    text.char_indices().flat_map(|(byte, ch)| {
        ch.to_lowercase()
            .map(move |lower| (lower, byte..byte + ch.len_utf8()))
    })
}

fn literal_ranges(text: &str, terms: &[String]) -> Vec<Range<usize>> {
    let folded: Vec<_> = mapped_chars(text).collect();
    let mut ranges = Vec::new();
    for term in terms {
        let chars: Vec<_> = term.chars().flat_map(char::to_lowercase).collect();
        if chars.is_empty() {
            continue;
        }
        for window in folded.windows(chars.len()) {
            if window.iter().map(|(ch, _)| ch).eq(chars.iter())
                && let (Some((_, first)), Some((_, last))) = (window.first(), window.last())
            {
                ranges.push(first.start..last.end);
            }
        }
    }
    ranges
}

fn pinyin_evidence(text: &str, needle: &[char]) -> Option<Vec<Range<usize>>> {
    match (
        pinyin_match(text, needle, false),
        pinyin_match(text, needle, true),
    ) {
        (Some(full), Some(initials)) => {
            if proximity(&full) >= proximity(&initials) {
                Some(full)
            } else {
                Some(initials)
            }
        }
        (Some(full), None) => Some(full),
        (None, Some(initials)) => Some(initials),
        (None, None) => None,
    }
}

fn proximity(ranges: &[Range<usize>]) -> i64 {
    let extent = ranges
        .first()
        .zip(ranges.last())
        .map_or(0, |(first, last)| last.end.saturating_sub(first.start));
    500_i64
        .saturating_sub(i64::try_from(extent).unwrap_or(i64::MAX))
        .max(10)
}

fn pinyin_match(text: &str, needle: &[char], initials: bool) -> Option<Vec<Range<usize>>> {
    let mut states = BTreeMap::from([(0, Vec::<Range<usize>>::new())]);
    for (byte, ch) in text.char_indices() {
        let range = byte..byte + ch.len_utf8();
        let norm = normalize_cjk_char(ch);
        let Some(pronunciations) = norm.to_pinyin_multi() else {
            continue;
        };
        let before = states.clone();
        for pronunciation in pronunciations {
            let syllable = if initials {
                pronunciation.first_letter()
            } else {
                pronunciation.plain()
            };
            let mut branch = before.clone();
            for letter in syllable.chars() {
                let current = branch.clone();
                for (matched, mut ranges) in current {
                    if needle.get(matched) != Some(&letter) {
                        continue;
                    }
                    ranges.push(range.clone());
                    retain_closest(&mut branch, matched + 1, ranges);
                }
            }
            for (matched, ranges) in branch {
                retain_closest(&mut states, matched, ranges);
            }
        }
    }
    states.remove(&needle.len())
}

fn retain_closest(
    states: &mut BTreeMap<usize, Vec<Range<usize>>>,
    matched: usize,
    ranges: Vec<Range<usize>>,
) {
    if states
        .get(&matched)
        .is_none_or(|old| proximity(&ranges) > proximity(old))
    {
        states.insert(matched, ranges);
    }
}

fn normalize_ranges(mut ranges: Vec<Range<usize>>) -> Vec<Range<usize>> {
    ranges.sort_by_key(|range| (range.start, range.end));
    ranges.dedup();
    ranges
}

fn make_excerpt(
    text: &str,
    ranges: &[Range<usize>],
    source: MatchSource,
) -> Result<SearchExcerpt, LomoError> {
    let first = ranges.first().map_or(0, |range| range.start);
    let (before, after) = text.split_at_checked(first).ok_or_else(|| {
        validation(
            "invalid_search_evidence",
            "excerpt center is not on a UTF-8 boundary",
        )
    })?;
    let start = before
        .char_indices()
        .rev()
        .nth(59)
        .map_or(0, |(index, _)| index);
    let end = after
        .char_indices()
        .nth(260)
        .map_or(text.len(), |(index, _)| first + index);
    let excerpt = text.get(start..end).ok_or_else(|| {
        validation(
            "invalid_search_evidence",
            "excerpt is not on UTF-8 boundaries",
        )
    })?;
    let mut highlights: Vec<_> = ranges
        .iter()
        .filter(|range| range.start >= start && range.end <= end)
        .map(|range| range.start - start..range.end - start)
        .collect();
    highlights.sort_by_key(|range| range.start);
    highlights.dedup();
    Ok(SearchExcerpt {
        text: excerpt.to_owned(),
        highlights,
        source,
        body_start: (source != MatchSource::Path).then_some(start),
    })
}
